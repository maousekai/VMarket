package com.vmarket.order;

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DeliveryMigrationTest {
    @Test void existingOrderSchemaCanUpgradeToDeliveryMigration() {
        String url = "jdbc:h2:mem:delivery-migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("2").load().migrate();
        var flyway = Flyway.configure().dataSource(url, "sa", "").load();
        flyway.migrate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("4");
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        assertThat(jdbc.queryForObject("select count(*) from delivery_order_outbox", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_name='ORDERS' and column_name='DELIVERY_VERSION'", Integer.class)).isEqualTo(1);
    }
}
