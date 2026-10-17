package com.vmarket.order;

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DeliveryMigrationTest {
    @Test
    void existingOrderSchemaCanUpgradeToDeliveryMigration() {
        String url = "jdbc:h2:mem:delivery-migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("2").load().migrate();
        var flyway = Flyway.configure().dataSource(url, "sa", "").load();
        flyway.migrate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("6");
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        assertThat(jdbc.queryForObject("select count(*) from event_outbox", Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from information_schema.columns where"
                                        + " table_name='ORDERS' and column_name='DELIVERY_VERSION'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void bothLegacyOutboxesKeepTheirPendingPayloadsWhenTheyAreUnified() {
        String url = "jdbc:h2:mem:outbox-merge;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("4").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        String body = "{\"orderId\":\"order-1\",\"status\":\"PROCESSING\"}";
        jdbc.update(
                "insert into event_outbox (id,event_type,payload,created_at) values"
                        + " ('payment-event','OrderStatusChanged',?,CURRENT_TIMESTAMP)",
                body);
        jdbc.update(
                "insert into delivery_order_outbox (id,event_type,payload,created_at) values"
                        + " ('delivery-event','OrderStatusChanged',?,CURRENT_TIMESTAMP)",
                body);
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        assertThat(jdbc.queryForList("select id from event_outbox order by id", String.class))
                .containsExactly("delivery-event", "payment-event");
        assertThat(jdbc.queryForList("select payload from event_outbox", String.class))
                .containsOnly(body);
        assertThat(jdbc.queryForList("select aggregate_id from event_outbox", String.class))
                .containsOnly("order-1");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from information_schema.tables where"
                                        + " table_name='DELIVERY_ORDER_OUTBOX'",
                                Integer.class))
                .isZero();
    }
}
