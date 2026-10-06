package com.vmarket.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;
import com.vmarket.payment.client.*;
import com.vmarket.payment.config.PayosProperties;
import com.vmarket.payment.repository.*;
import com.vmarket.payment.service.*;
import com.vmarket.payment.event.*;
import com.vmarket.payment.entity.PaymentTransaction;
import com.vmarket.events.EventPublisher;

@SpringBootTest @AutoConfigureMockMvc
class PaymentApiTest {
    private static final String ORDER = "01" + "0".repeat(24);
    private static final String SECRET = "test-only-secret-0123456789-0123456789-0123456789";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired PaymentRepository payments;
    @Autowired PaymentOrderStateRepository states;
    @Autowired RefundRepository refunds;
    @Autowired OutboxRepository events;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired PaymentStore store;
    @Autowired PayosSignature signature;
    @Autowired PayosProperties properties;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @MockitoBean OrderClient orders;
    @MockitoBean PayosClient payos;
    @MockitoBean EventPublisher publisher;
    @MockitoBean Clock clock;
    Instant now = Instant.parse("2026-10-06T15:00:00Z");

    @BeforeEach void setup() {
        refunds.deleteAll(); payments.deleteAll(); states.deleteAll(); events.deleteAll();
        when(clock.instant()).thenReturn(now);
        when(orders.getOwn(anyString(), eq(ORDER))).thenReturn(new OrderClient.OrderView(ORDER, "WAITING_PAYMENT", new BigDecimal("3000.00"), "PAYOS", now.plusSeconds(900), true));
        when(payos.create(anyLong(), eq(3000L), any())).thenAnswer(a -> new PayosClient.Link("test-link", "https://pay.payos.vn/test", "test-qr"));
    }
    String token(String who, String role) {
        return "Bearer " + Jwts.builder().issuer("auth-service").subject(who).claim("roles", List.of(role))
            .expiration(Date.from(Instant.now().plusSeconds(600))).signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }
    Long create() throws Exception {
        var response = mvc.perform(post("/api/payments").header("Authorization", token("alice", "BUYER"))
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("orderId", ORDER))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(3000)).andExpect(jsonPath("$.status").value("PENDING")).andReturn();
        return mapper.readTree(response.getResponse().getContentAsString()).path("id").asLong();
    }
    Map<String,Object> data(Long id) {
        return new TreeMap<>(Map.of("orderCode", id, "amount", 3000, "paymentLinkId", "test-link", "reference", "bank-" + id, "currency", "VND", "code", "00"));
    }
    void webhook(Map<String,Object> data, String sig) throws Exception {
        mvc.perform(post("/api/payments/webhooks/payos").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("data", data, "signature", sig)))) .andExpect(status().isOk());
    }
    @Test void authenticationAndOwnership() throws Exception {
        mvc.perform(get("/api/payments")).andExpect(status().isUnauthorized());
        Long id = create();
        mvc.perform(get("/api/payments/" + id).header("Authorization", token("bob", "BUYER"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/payments/admin/refunds").header("Authorization", token("alice", "BUYER"))).andExpect(status().isForbidden());
    }
    @Test void duplicateCreateAndWebhookEmitOnce() throws Exception {
        Long id = create(); assertThat(create()).isEqualTo(id); verify(payos, times(1)).create(anyLong(), anyLong(), any());
        var data = data(id); String sig = signature.sign(data, properties.getChecksumKey());
        webhook(data, sig); webhook(data, sig);
        assertThat(payments.findById(id).orElseThrow().getStatus()).isEqualTo("SUCCEEDED");
        assertThat(events.findAll()).extracting(OutboxEvent::getEventType).containsExactly("PaymentSucceeded");
    }
    @Test void unsignedOuterSuccessCannotChangeSignedFailure() throws Exception {
        Long id = create(); var data = data(id); data.put("code", "99");
        webhook(data, signature.sign(data, properties.getChecksumKey()));
        assertThat(payments.findById(id).orElseThrow().getPaidAt()).isNull();
        assertThat(payments.findById(id).orElseThrow().getStatus()).isEqualTo("FAILED");
        assertThat(events.findAll()).extracting(OutboxEvent::getEventType).containsExactly("PaymentFailed");
        assertThat(create()).isEqualTo(id);
    }
    @Test void forgedSignatureAndWrongAmountAreRejected() throws Exception {
        Long id = create(); var data = data(id);
        mvc.perform(post("/api/payments/webhooks/payos").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("data", data, "signature", "0".repeat(64)))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_SIGNATURE"));
        data.put("amount", 1);
        mvc.perform(post("/api/payments/webhooks/payos").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("data", data, "signature", signature.sign(data, properties.getChecksumKey())))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("PAYMENT_MISMATCH"));
        assertThat(events.count()).isZero();
    }
    @Test void expiryAndLateSuccessProduceRefundInsteadOfRevivingOrder() throws Exception {
        Long id = create(); when(clock.instant()).thenReturn(now.plusSeconds(901)); store.expire(id); store.expire(id);
        var data = data(id); webhook(data, signature.sign(data, properties.getChecksumKey()));
        assertThat(events.findAll()).extracting(OutboxEvent::getEventType).containsExactly("PaymentFailed");
        assertThat(refunds.findByPaymentId(id)).isPresent();
    }
    @Test void cancelSuccessRaceRefundsExactlyOnce() throws Exception {
        Long id = create(); store.cancelled(ORDER);
        var data = data(id); webhook(data, signature.sign(data, properties.getChecksumKey())); store.cancelled(ORDER);
        assertThat(refunds.count()).isEqualTo(1); assertThat(events.count()).isZero();
    }
    @Test void cancellationWaitsForConcurrentWebhookAndPreservesItsCommittedPayment() throws Exception {
        Long id = create();
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var cancelling = new java.util.concurrent.CountDownLatch(1);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try {
            var paid = workers.submit(() -> transaction.executeWithoutResult(tx -> {
                payments.lock(id).orElseThrow(); locked.countDown();
                try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                store.webhook(data(id));
            }));
            assertThat(locked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var cancelled = workers.submit(() -> { cancelling.countDown(); store.cancelled(ORDER); });
            assertThat(cancelling.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            // A locked order lookup cannot return the pre-webhook managed instance.
            assertThatThrownBy(() -> cancelled.get(100, java.util.concurrent.TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown(); paid.get(5, java.util.concurrent.TimeUnit.SECONDS); cancelled.get(5, java.util.concurrent.TimeUnit.SECONDS);
            var saved = payments.findById(id).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo("SUCCEEDED"); assertThat(saved.getPaidAt()).isNotNull();
            assertThat(saved.getReference()).isEqualTo("bank-" + id); assertThat(refunds.count()).isEqualTo(1);
        } finally { release.countDown(); workers.shutdownNow(); }
    }
    @Test void signedWebhookRecoversLinkLostAfterProviderTimeout() throws Exception {
        when(payos.create(anyLong(), anyLong(), any())).thenThrow(com.vmarket.payment.exception.ApiException.unavailable("PAYOS_UNAVAILABLE", "timeout"));
        mvc.perform(post("/api/payments").header("Authorization", token("alice", "BUYER")).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("orderId", ORDER)))).andExpect(status().isServiceUnavailable());
        var id = payments.findByOrderId(ORDER).orElseThrow().getId();
        var data = data(id); webhook(data, signature.sign(data, properties.getChecksumKey()));
        assertThat(payments.findById(id).orElseThrow().getStatus()).isEqualTo("SUCCEEDED");
        assertThat(events.count()).isEqualTo(1);
    }
    @Test void recoveredLinkWithoutQrExposesCheckoutFallback() throws Exception {
        doReturn(new PayosClient.Link("test-link", "https://pay.payos.vn/test", null)).when(payos).create(anyLong(), anyLong(), any());
        Long id = create();
        mvc.perform(get("/api/payments/" + id).header("Authorization", token("alice", "BUYER")))
            .andExpect(jsonPath("$.qrAvailable").value(false)).andExpect(jsonPath("$.checkoutUrl").value("https://pay.payos.vn/test"));
    }
    @Test void manualRefundIsAdminOnlyAndAudited() throws Exception {
        Long id = create(); var data = data(id); webhook(data, signature.sign(data, properties.getChecksumKey())); store.cancelled(ORDER);
        String refundId = refunds.findByPaymentId(id).orElseThrow().getId();
        String body = "{\"transferReference\":\"manual-bank-reference\"}";
        mvc.perform(put("/api/payments/admin/refunds/" + refundId + "/confirm").header("Authorization", token("alice", "BUYER")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        for (int i=0; i<2; i++) mvc.perform(put("/api/payments/admin/refunds/" + refundId + "/confirm").header("Authorization", token("admin", "ADMIN")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        assertThat(refunds.findById(refundId).orElseThrow().getConfirmedBy()).isEqualTo("admin");
    }
    @Test void providerTimeoutRetriesTheSameCode() throws Exception {
        when(payos.create(anyLong(), anyLong(), any())).thenThrow(com.vmarket.payment.exception.ApiException.unavailable("PAYOS_UNAVAILABLE", "timeout"));
        mvc.perform(post("/api/payments").header("Authorization", token("alice", "BUYER")).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("orderId", ORDER)))).andExpect(status().isServiceUnavailable());
        var before = payments.findByOrderId(ORDER).orElseThrow();
        assertThat(before.getStatus()).isEqualTo("CREATED");
        doReturn(new PayosClient.Link("test-link", "https://pay.payos.vn/test", "test-qr")).when(payos).create(anyLong(), anyLong(), any());
        assertThat(create()).isEqualTo(before.getId());
    }
    @Test void stockMustBeReservedBeforePayos() throws Exception {
        when(orders.getOwn(anyString(), eq(ORDER))).thenReturn(new OrderClient.OrderView(ORDER, "WAITING_PAYMENT", new BigDecimal("3000"), "PAYOS", now.plusSeconds(900), false));
        mvc.perform(post("/api/payments").header("Authorization", token("alice", "BUYER")).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("orderId", ORDER)))).andExpect(status().isConflict());
        verifyNoInteractions(payos);
    }
    @Test void codDoesNotContactProvider() throws Exception {
        when(orders.getOwn(anyString(), eq(ORDER))).thenReturn(new OrderClient.OrderView(ORDER, "PENDING", new BigDecimal("3000"), "COD", null, false));
        mvc.perform(post("/api/payments").header("Authorization", token("alice", "BUYER")).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("orderId", ORDER))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COD_PENDING")); verifyNoInteractions(payos);
    }
    @Test void outboxSurvivesBrokerOutage() throws Exception {
        Long id = create(); var data = data(id); webhook(data, signature.sign(data, properties.getChecksumKey()));
        doThrow(new org.springframework.amqp.AmqpException("offline")).when(publisher).publish(anyString(), any());
        assertThatThrownBy(() -> dispatcher.dispatch()).isInstanceOf(org.springframework.amqp.AmqpException.class);
        assertThat(events.count()).isEqualTo(1); doNothing().when(publisher).publish(anyString(), any()); dispatcher.dispatch(); assertThat(events.count()).isZero();
    }
    @Test void codCollectionWithoutBuyerPaymentRequestIsDuplicateSafe() {
        var collected = new com.vmarket.events.CodCollected(ORDER, "alice", 3000L, "delivery-1");
        store.codCollected(collected); store.codCollected(collected);
        var payment = payments.findByOrderId(ORDER).orElseThrow();
        assertThat(payment.getMethod()).isEqualTo("COD"); assertThat(payment.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(payments.count()).isEqualTo(1); assertThat(events.count()).isZero(); verifyNoInteractions(payos);
    }
    @Test void cancellationBeforeCodCollectionWithoutPaymentStillCreatesRefund() {
        store.cancelled(ORDER);
        store.codCollected(new com.vmarket.events.CodCollected(ORDER, "alice", 3000L, "delivery-1"));
        assertThat(refunds.count()).isEqualTo(1);
    }
    @Test void cancellationAfterCodCollectionWithoutPaymentCreatesRefund() {
        store.codCollected(new com.vmarket.events.CodCollected(ORDER, "alice", 3000L, "delivery-1"));
        store.cancelled(ORDER); store.cancelled(ORDER);
        assertThat(refunds.count()).isEqualTo(1);
    }
    @Test void concurrentFirstCodAndCancellationPreserveRefund() throws Exception {
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var paid = workers.submit(() -> { start.await(); store.codCollected(new com.vmarket.events.CodCollected(ORDER, "alice", 3000L, "delivery-1")); return null; });
            var cancel = workers.submit(() -> { start.await(); store.cancelled(ORDER); return null; });
            start.countDown(); paid.get(10, java.util.concurrent.TimeUnit.SECONDS); cancel.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(payments.count()).isEqualTo(1); assertThat(refunds.count()).isEqualTo(1);
        } finally { workers.shutdownNow(); }
    }
    @Test void failingCancellationBatchDoesNotStarveTheNextPayment() {
        var ids = new ArrayList<Long>();
        for (int i = 0; i < 101; i++) {
            var p = new PaymentTransaction(); p.setOrderId(String.format("%026d", i)); p.setBuyerId("alice"); p.setAmount(3000L);
            p.setMethod("PAYOS"); p.setStatus("EXPIRED"); p.setCreatedAt(now); p.setCancelPending(true); p.setNextCancelAttemptAt(now);
            ids.add(payments.saveAndFlush(p).getId());
        }
        doThrow(com.vmarket.payment.exception.ApiException.unavailable("PAYOS_UNAVAILABLE", "offline")).when(payos).cancel(anyLong());
        doNothing().when(payos).cancel(ids.get(100));
        var job = new PaymentExpiryJob(payments, store, payos, clock);
        job.run(); job.run();
        verify(payos).cancel(ids.get(100));
        assertThat(payments.findById(ids.get(100)).orElseThrow().isCancelPending()).isFalse();
        assertThat(payments.findById(ids.get(0)).orElseThrow().getCancelAttempts()).isEqualTo(1);
    }
    @Test void providerPaidWithoutWebhookRemainsInDurableAdminReconciliationQueue() throws Exception {
        Long id = create(); when(clock.instant()).thenReturn(now.plusSeconds(901)); store.expire(id);
        doThrow(com.vmarket.payment.exception.ApiException.unavailable("PAYOS_PAID_RECONCILIATION_REQUIRED", "paid")).when(payos).cancel(id);
        store.cancelRemote(id, payos); when(clock.instant()).thenReturn(now.plusSeconds(912)); store.cancelRemote(id, payos);
        assertThat(payments.findById(id).orElseThrow().isReconciliationRequired()).isTrue();
        assertThat(payments.findById(id).orElseThrow().isCancelPending()).isTrue();
        mvc.perform(get("/api/payments/admin/reconciliations").header("Authorization", token("alice", "BUYER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/payments/admin/reconciliations").header("Authorization", token("admin", "ADMIN")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].reconciliationRequired").value(true));
        var data = data(id); webhook(data, signature.sign(data, properties.getChecksumKey()));
        assertThat(payments.findById(id).orElseThrow().isReconciliationRequired()).isFalse();
        assertThat(refunds.count()).isEqualTo(1);
    }
}
