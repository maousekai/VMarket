package com.vmarket.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.vmarket.events.CodCollected;
import com.vmarket.events.EventPublisher;
import com.vmarket.payment.client.OrderClient;
import com.vmarket.payment.event.Outbox;
import com.vmarket.payment.event.OutboxDispatcher;
import com.vmarket.payment.event.OutboxRepository;
import com.vmarket.payment.repository.*;
import com.vmarket.payment.service.PaymentStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        properties = {
            "spring.datasource.hikari.maximum-pool-size=2",
            "spring.datasource.hikari.minimum-idle=0",
            "spring.datasource.hikari.connection-timeout=2000"
        })
class PaymentPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired PaymentStore store;
    @Autowired PaymentRepository payments;
    @Autowired PaymentOrderStateRepository states;
    @Autowired RefundRepository refunds;
    @Autowired OutboxRepository events;
    @Autowired Outbox outbox;
    @Autowired OutboxDispatcher dispatcher;
    @MockitoBean EventPublisher publisher;
    @Autowired com.zaxxer.hikari.HikariDataSource dataSource;

    @BeforeEach
    void clean() {
        dataSource.setMaximumPoolSize(1);
        dataSource.getHikariPoolMXBean().softEvictConnections();
        refunds.deleteAll();
        payments.deleteAll();
        states.deleteAll();
        events.deleteAll();
    }

    @Test
    void concurrentRequestsCompleteOnRealPostgresWithASingleConnection() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        var results = new ArrayList<Future<?>>();
        try {
            for (int i = 0; i < 8; i++) {
                final int index = i;
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    store.codCollected(
                                            new CodCollected(
                                                    "pg-pool-" + index,
                                                    "buyer",
                                                    3000L,
                                                    "pg-ref-" + index));
                                    return null;
                                }));
            }
            start.countDown();
            for (var result : results) result.get(15, TimeUnit.SECONDS);
            assertThat(payments.count()).isEqualTo(8);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void referenceConstraintIsScopedByMethodOnPostgres() {
        store.codCollected(new CodCollected("cod-order", "buyer", 3000L, "same-bank-reference"));
        Instant now = Instant.now();
        Long id =
                store.reserve(
                        "buyer",
                        new OrderClient.OrderView(
                                "payos-order",
                                "WAITING_PAYMENT",
                                new BigDecimal("3000"),
                                "PAYOS",
                                now.plusSeconds(900),
                                true));
        store.webhook(
                Map.of(
                        "orderCode",
                        id,
                        "amount",
                        3000,
                        "currency",
                        "VND",
                        "paymentLinkId",
                        "pg-link",
                        "reference",
                        "same-bank-reference",
                        "code",
                        "00",
                        "transactionDateTime",
                        now.toString()));
        assertThat(payments.count()).isEqualTo(2);
        assertThat(payments.findAll()).allSatisfy(p -> assertThat(p.getPaidAt()).isNotNull());
    }

    @Test
    void confirmedRowsRemainDeletedWhenAFollowingRowFailsOnPostgres() {
        outbox.add("Good", Map.of("orderId", "pg-good"));
        outbox.add("Bad", Map.of("orderId", "pg-bad"));
        doThrow(new IllegalStateException("poison")).when(publisher).publish(eq("Bad"), any());
        dispatcher.dispatch();
        assertThat(events.findAll())
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.getEventType()).isEqualTo("Bad");
                            assertThat(event.getRetryAttempts()).isEqualTo(1);
                        });
        verify(publisher).publish(eq("Good"), any());
    }
}
