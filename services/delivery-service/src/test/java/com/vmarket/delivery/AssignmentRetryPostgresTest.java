package com.vmarket.delivery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.vmarket.delivery.client.DeliveryClients;
import com.vmarket.delivery.repository.DeliveryRepository;
import com.vmarket.delivery.service.AssignmentRetryWorker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AssignmentRetryPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired AssignmentRetryWorker worker;
    @Autowired DeliveryRepository deliveries;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean DeliveryClients clients;
    @MockitoBean StringRedisTemplate redis;

    @BeforeEach
    void seed() {
        for (String table :
                List.of(
                        "delivery_outbox",
                        "delivery_status_history",
                        "delivery_assignments",
                        "shippers")) jdbc.update("delete from " + table);
        jdbc.update(
                """
insert into shippers (id,provision_key,name,phone,province,district,active,created_at)
values ('shipper','provision','Shipper','0900000000','da nang','hai chau',true,CURRENT_TIMESTAMP)
""");
        jdbc.update(
                """
insert into delivery_assignments
  (id,order_id,buyer_id,status,payment_method,cod_amount,province,district,order_snapshot,created_at,updated_at)
values ('assignment','order','buyer','WAITING_ASSIGNMENT','COD',3000,'da nang','hai chau','{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
""");
    }

    @Test
    void postgresLeaseExcludesSecondWorkerWhileAuthIsOutsideTransaction() throws Exception {
        CountDownLatch checking = new CountDownLatch(1), release = new CountDownLatch(1);
        when(clients.activeShipper(anyString()))
                .thenAnswer(
                        inv -> {
                            assertThat(
                                            org.springframework.transaction.support
                                                    .TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            checking.countDown();
                            assertThat(release.await(8, TimeUnit.SECONDS)).isTrue();
                            return true;
                        });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(worker::runBatch);
            assertThat(checking.await(5, TimeUnit.SECONDS)).isTrue();
            pool.submit(worker::runBatch).get(3, TimeUnit.SECONDS);
            release.countDown();
            first.get(8, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        verify(clients, times(1)).activeShipper("shipper");
        assertSingleAssignment();
    }

    @Test
    void postgresExpiredLeaseCanBeTakenOverWithoutDoubleAssignment() throws Exception {
        CountDownLatch checking = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(clients.activeShipper(anyString()))
                .thenAnswer(
                        inv -> {
                            if (calls.incrementAndGet() == 1) {
                                checking.countDown();
                                assertThat(release.await(8, TimeUnit.SECONDS)).isTrue();
                            }
                            return true;
                        });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var stale = pool.submit(worker::runBatch);
            assertThat(checking.await(5, TimeUnit.SECONDS)).isTrue();
            jdbc.update(
                    "update delivery_assignments set assignment_claim_until=?",
                    Timestamp.from(Instant.EPOCH));
            pool.submit(worker::runBatch).get(5, TimeUnit.SECONDS);
            release.countDown();
            stale.get(8, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertSingleAssignment();
        assertThat(
                        jdbc.queryForObject(
                                "select assignment_attempts from delivery_assignments",
                                Integer.class))
                .isEqualTo(2);
    }

    private void assertSingleAssignment() {
        assertThat(deliveries.find("assignment", false).getShipperId()).isEqualTo("shipper");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from delivery_outbox where"
                                    + " event_type='DeliveryAssigned'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from delivery_status_history", Integer.class))
                .isEqualTo(1);
    }
}
