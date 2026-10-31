package com.vmarket.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.vmarket.delivery.domain.Assignment;
import com.vmarket.delivery.domain.DeliveryStatus;
import com.vmarket.delivery.repository.DeliveryRepository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.OffsetDateTime;
import java.util.UUID;

class DeliveryOutboxMigrationTest {
    @Test
    void existingPendingEventsSurviveUpgradeAndNewEventsGetRetryDefaults() throws Exception {
        String url =
                "jdbc:h2:mem:outbox-upgrade-"
                        + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("1").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        var repo = new DeliveryRepository(jdbc);
        var a = new Assignment();
        a.setId(UUID.randomUUID().toString());
        a.setOrderId("order-upgrade");
        a.setBuyerId("buyer");
        a.setStatus(DeliveryStatus.WAITING_ASSIGNMENT);
        a.setAttempt(1);
        a.setPaymentMethod("COD");
        a.setCodAmount(1000);
        a.setProvince("province");
        a.setDistrict("district");
        a.setOrderSnapshot("{}");
        repo.insert(a);
        repo.outbox(a, "DeliveryAssigned", "{\"pending\":true}");
        jdbc.update(
                "update delivery_assignments set version=9, province=?, district=?",
                "TP. Đà Nẵng",
                "Quận Hải Châu");
        jdbc.update(
                "insert into route_logs (id,assignment_id,sequence,latitude,longitude,recorded_at)"
                        + " values ('old-point',?,7,16,108,CURRENT_TIMESTAMP)",
                a.getId());
        var flyway = Flyway.configure().dataSource(url, "sa", "").load();
        flyway.migrate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("6");
        try (var c = new DriverManagerDataSource(url, "sa", "").getConnection()) {
            assertThat(columnIsNullable(c, "shippers", "district")).isTrue();
            assertThat(columnIsNullable(c, "delivery_assignments", "district")).isTrue();
            assertThat(columnIsNullable(c, "shippers", "province")).isFalse();
            assertThat(columnIsNullable(c, "delivery_assignments", "province")).isFalse();
        }
        assertThat(jdbc.queryForObject("select payload from delivery_outbox", String.class))
                .isEqualTo("{\"pending\":true}");
        assertThat(jdbc.queryForObject("select retry_attempts from delivery_outbox", Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select next_attempt_at from delivery_outbox",
                                OffsetDateTime.class))
                .isNotNull();
        var upgraded = repo.find(a.getId(), false);
        assertThat(upgraded.getVersion()).isEqualTo(9);
        assertThat(upgraded.getTrackingSequence()).isEqualTo(9);
        assertThat(upgraded.getLocationSequence()).isEqualTo(7);
        assertThat(upgraded.getProvince()).isEqualTo("da nang");
        assertThat(upgraded.getDistrict()).isEqualTo("hai chau");
        repo.outbox(a, "DeliveryStatusChanged", "{}");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from delivery_outbox where retry_attempts=0 and"
                                        + " next_attempt_at<=CURRENT_TIMESTAMP",
                                Integer.class))
                .isEqualTo(2);
    }

    private static boolean columnIsNullable(java.sql.Connection c, String table, String column)
            throws Exception {
        var meta = c.getMetaData();
        for (String t : new String[] {table, table.toUpperCase()}) {
            for (String col : new String[] {column, column.toUpperCase()}) {
                try (var rs = meta.getColumns(null, null, t, col)) {
                    if (rs.next()) {
                        return rs.getInt("NULLABLE") == java.sql.DatabaseMetaData.columnNullable;
                    }
                }
            }
        }
        return false;
    }
}
