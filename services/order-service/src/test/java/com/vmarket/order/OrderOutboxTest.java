package com.vmarket.order;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.vmarket.events.EventPublisher;
import com.vmarket.events.OrderStatusChanged;
import com.vmarket.order.event.Outbox;
import com.vmarket.order.event.OutboxDispatcher;
import com.vmarket.order.event.OutboxMonitor;
import com.vmarket.order.event.OutboxRecovery;
import com.vmarket.order.event.OutboxRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@AutoConfigureMockMvc
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
    @Autowired OutboxRecovery recovery;
    @Autowired MockMvc mvc;
    @MockitoBean EventPublisher publisher;

    @BeforeEach
    void clean() {
        events.deleteAll();
        jdbc.update("delete from outbox_replay_audit");
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

    @Test
    void adminReplayRecoversDeadPredecessorBeforeItsSuccessorAndKeepsAudit() throws Exception {
        outbox.add("Poison", Map.of("orderId", "order-a", "private", "customer-address"));
        var failed = events.findAll().get(0);
        jdbc.update(
                "update event_outbox set state='DEAD', retry_attempts=10, last_error='Timeout'");
        outbox.add("After", Map.of("orderId", "order-a"));
        dispatcher.dispatch();
        verifyNoInteractions(publisher);
        assertThat(recovery.status().dead()).isEqualTo(1);
        assertThat(recovery.status().blocked()).isEqualTo(1);
        var metrics = new SimpleMeterRegistry();
        new OutboxMonitor(recovery, metrics).inspect();
        assertThat(metrics.get("vmarket.order.outbox.dead").gauge().value()).isEqualTo(1);
        assertThat(metrics.get("vmarket.order.outbox.blocked").gauge().value()).isEqualTo(1);

        String admin =
                TestTokens.bearer(
                        TestTokens.accessToken(
                                "test-only-secret-0123456789-0123456789-0123456789",
                                "admin-replay",
                                java.util.List.of("ADMIN")));
        mvc.perform(get("/api/orders/admin/outbox/dead").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("customer-address"))));
        mvc.perform(
                        post("/api/orders/admin/outbox/{id}/replay", failed.getId())
                                .header("Authorization", admin)
                                .contentType("application/json")
                                .content("{\"reason\":\"Đã sửa binding và kiểm tra consumer\"}"))
                .andExpect(status().isNoContent());
        assertThat(events.findById(failed.getId()).orElseThrow().getPayload())
                .isEqualTo(failed.getPayload());
        // A repeated action cannot reset attempts for an event that is already PENDING.
        mvc.perform(
                        post("/api/orders/admin/outbox/{id}/replay", failed.getId())
                                .header("Authorization", admin)
                                .contentType("application/json")
                                .content("{\"reason\":\"retry\"}"))
                .andExpect(status().isConflict());
        dispatcher.dispatch();
        assertThat(events.count()).isZero();
        var types = ArgumentCaptor.forClass(String.class);
        verify(publisher, times(2)).publish(types.capture(), any());
        assertThat(types.getAllValues()).containsExactly("Poison", "After");
        var audit =
                jdbc.queryForMap(
                        "select * from outbox_replay_audit where event_id=?", failed.getId());
        assertThat(audit.get("replayed_by")).isEqualTo("admin-replay");
        assertThat(audit.get("previous_attempts")).isEqualTo(10);
        assertThat(audit.get("previous_error")).isEqualTo("Timeout");
        assertThat(recovery.status().dead()).isZero();
        assertThat(recovery.status().blocked()).isZero();
    }

    @Test
    void recoveryRequiresAdminAndAReason() throws Exception {
        outbox.add("Poison", Map.of("orderId", "order-a"));
        var id = events.findAll().get(0).getId();
        jdbc.update("update event_outbox set state='DEAD'");
        String secret = "test-only-secret-0123456789-0123456789-0123456789";
        for (String role : java.util.List.of("BUYER", "SELLER", "SHIPPER")) {
            var token =
                    TestTokens.bearer(
                            TestTokens.accessToken(secret, "user", java.util.List.of(role)));
            mvc.perform(get("/api/orders/admin/outbox/dead").header("Authorization", token))
                    .andExpect(status().isForbidden());
            mvc.perform(
                            post("/api/orders/admin/outbox/{id}/replay", id)
                                    .header("Authorization", token)
                                    .contentType("application/json")
                                    .content("{\"reason\":\"test\"}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/orders/admin/outbox/status")).andExpect(status().isUnauthorized());
        var admin =
                TestTokens.bearer(
                        TestTokens.accessToken(secret, "admin", java.util.List.of("ADMIN")));
        mvc.perform(
                        post("/api/orders/admin/outbox/{id}/replay", id)
                                .header("Authorization", admin)
                                .contentType("application/json")
                                .content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());
        assertThat(events.findById(id).orElseThrow().getState()).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("select count(*) from outbox_replay_audit", Long.class))
                .isZero();
    }

    @Test
    void lostConfirmRetriesSamePayloadBeforeSuccessor() {
        outbox.add("AcceptedButConfirmLost", Map.of("orderId", "order-a"));
        outbox.add("After", Map.of("orderId", "order-a"));
        doThrow(new IllegalStateException("broker accepted; confirm timed out"))
                .doNothing()
                .when(publisher)
                .publish(eq("AcceptedButConfirmLost"), any());
        dispatcher.dispatch();
        assertThat(events.count()).isEqualTo(2);
        verify(publisher, never()).publish(eq("After"), any());
        jdbc.update("update event_outbox set next_attempt_at=?", Timestamp.from(Instant.EPOCH));
        dispatcher.dispatch();
        assertThat(events.count()).isZero();
        var payloads = ArgumentCaptor.forClass(Object.class);
        verify(publisher, times(2)).publish(eq("AcceptedButConfirmLost"), payloads.capture());
        assertThat(payloads.getAllValues().get(0)).isEqualTo(payloads.getAllValues().get(1));
        verify(publisher).publish(eq("After"), any());
    }
}
