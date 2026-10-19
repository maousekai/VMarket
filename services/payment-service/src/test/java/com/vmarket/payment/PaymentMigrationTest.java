package com.vmarket.payment;

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.OffsetDateTime;

class PaymentMigrationTest {
    @Test
    void pendingPayloadsAndOldPaymentDeadlinesSurviveUpgrade() {
        String url = "jdbc:h2:mem:payment-upgrade;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("1").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        jdbc.update(
                "insert into payment_transactions"
                    + " (id,order_id,buyer_id,amount,method,status,created_at,expires_at) values"
                    + " (1,'old-order','buyer',3000,'PAYOS','PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update(
                "insert into event_outbox (id,event_type,payload,created_at) values"
                    + " ('old-event','PaymentSucceeded','{\"orderId\":\"old-order\"}',CURRENT_TIMESTAMP)");
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        assertThat(jdbc.queryForObject("select payload from event_outbox", String.class))
                .isEqualTo("{\"orderId\":\"old-order\"}");
        assertThat(jdbc.queryForObject("select aggregate_id from event_outbox", String.class))
                .isEqualTo("old-order");
        var deadline =
                jdbc.queryForObject(
                        "select expires_at from payment_transactions", OffsetDateTime.class);
        assertThat(
                        jdbc.queryForObject(
                                "select link_expires_at from payment_transactions",
                                OffsetDateTime.class))
                .isEqualTo(deadline);
        assertThat(
                        jdbc.queryForObject(
                                "select settlement_expires_at from payment_transactions",
                                OffsetDateTime.class))
                .isEqualTo(deadline.plusSeconds(120));
    }
}
