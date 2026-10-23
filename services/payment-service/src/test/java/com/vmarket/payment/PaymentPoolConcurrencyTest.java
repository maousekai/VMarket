package com.vmarket.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.vmarket.events.CodCollected;
import com.vmarket.payment.repository.PaymentRepository;
import com.vmarket.payment.service.PaymentStore;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.concurrent.*;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:payment-small-pool;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.hikari.maximum-pool-size=2",
            "spring.datasource.hikari.minimum-idle=0",
            "spring.datasource.hikari.connection-timeout=1000"
        })
class PaymentPoolConcurrencyTest {
    @Autowired PaymentStore store;
    @Autowired PaymentRepository payments;
    @Autowired com.zaxxer.hikari.HikariDataSource dataSource;

    @Test
    void concurrentRequestsNeedOnlyOneConnectionEach() throws Exception {
        // Flyway needs a separate metadata/migration connection during startup.
        dataSource.setMaximumPoolSize(1);
        dataSource.getHikariPoolMXBean().softEvictConnections();
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
                                                    "pool-" + index,
                                                    "buyer",
                                                    3000L,
                                                    "ref-" + index));
                                    return null;
                                }));
            }
            start.countDown();
            for (var result : results) result.get(10, TimeUnit.SECONDS);
            assertThat(payments.count()).isEqualTo(8);
        } finally {
            pool.shutdownNow();
        }
    }
}
