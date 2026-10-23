package com.vmarket.order;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.vmarket.events.EventPublisher;
import com.vmarket.events.OrderStatusChanged;
import com.vmarket.order.event.Outbox;
import com.vmarket.order.event.OutboxDispatcher;
import com.vmarket.order.event.OutboxRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:order-outbox;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.show-sql=false"
        })
class OrderOutboxTest {
    @Autowired Outbox outbox;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired OutboxRepository events;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean EventPublisher publisher;

    @BeforeEach
    void clean() {
        events.deleteAll();
    }

    @Test
    void failedOrderCannotStarveOtherOrdersAndItsOwnEventsRemainOrdered() {
        outbox.add("Poison", Map.of("orderId", "order-a"));
        outbox.add(
                "OrderStatusChanged",
                new OrderStatusChanged("order-a", "PROCESSING", "SHIPPED", "COD"));
        outbox.add(
                "OrderStatusChanged",
                new OrderStatusChanged("order-b", "PENDING", "PROCESSING", "COD"));
        doThrow(new IllegalStateException("unroutable"))
                .when(publisher)
                .publish(eq("Poison"), any());
        dispatcher.dispatch();
        assertThat(events.count()).isEqualTo(2);
        assertThat(events.findAll())
                .allSatisfy(event -> assertThat(event.getAggregateId()).isEqualTo("order-a"));
        verify(publisher, times(1)).publish(eq("OrderStatusChanged"), any());
        verify(publisher, times(1)).publish(eq("Poison"), any());
        dispatcher.dispatch();
        verifyNoMoreInteractions(publisher);

        reset(publisher);
        jdbc.update("update event_outbox set next_attempt_at=?", Timestamp.from(Instant.EPOCH));
        dispatcher.dispatch();
        dispatcher.dispatch();
        assertThat(events.count()).isZero();
        var types = ArgumentCaptor.forClass(String.class);
        verify(publisher, times(2)).publish(types.capture(), any());
        assertThat(types.getAllValues()).containsExactly("Poison", "OrderStatusChanged");
    }

    @Test
    void deadEventStopsRetryingAndRemainsAvailableForOperatorRecovery() {
        outbox.add("Poison", Map.of("orderId", "order-a"));
        doThrow(new IllegalStateException("offline")).when(publisher).publish(eq("Poison"), any());
        for (int i = 0; i < 12; i++) {
            jdbc.update("update event_outbox set next_attempt_at=?", Timestamp.from(Instant.EPOCH));
            dispatcher.dispatch();
        }
        assertThat(events.findAll())
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.getState()).isEqualTo("DEAD");
                            assertThat(event.getRetryAttempts()).isEqualTo(10);
                            assertThat(event.getPayload()).contains("order-a");
                        });
        verify(publisher, times(10)).publish(eq("Poison"), any());
        outbox.add(
                "OrderStatusChanged",
                new OrderStatusChanged("order-b", "PENDING", "PROCESSING", "COD"));
        dispatcher.dispatch();
        verify(publisher).publish(eq("OrderStatusChanged"), any());
        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void concurrentWorkersCannotPublishTheSameLockedRow() throws Exception {
        outbox.add(
                "OrderStatusChanged",
                new OrderStatusChanged("order-a", "PENDING", "PROCESSING", "COD"));
        CountDownLatch publishing = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            publishing.countDown();
                            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                            return null;
                        })
                .when(publisher)
                .publish(anyString(), any());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(dispatcher::dispatch);
            assertThat(publishing.await(5, TimeUnit.SECONDS)).isTrue();
            pool.submit(dispatcher::dispatch).get(3, TimeUnit.SECONDS);
            verify(publisher, times(1)).publish(anyString(), any());
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(events.count()).isZero();
    }
}
