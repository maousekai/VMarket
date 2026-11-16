package com.vmarket.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.vmarket.events.EventPublisher;
import com.vmarket.payment.client.*;
import com.vmarket.payment.config.VnpayProperties;
import com.vmarket.payment.entity.PaymentTransaction;
import com.vmarket.payment.event.*;
import com.vmarket.payment.repository.*;
import com.vmarket.payment.service.*;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@SpringBootTest
@AutoConfigureMockMvc
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
    @Autowired VnpaySignature signature;
    @Autowired VnpayProperties properties;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @MockitoBean OrderClient orders;
    @MockitoBean PaymentGatewayClient gateway;
    @MockitoBean EventPublisher publisher;
    @MockitoBean Clock clock;
    Instant now = Instant.parse("2026-10-06T15:00:00Z");

    @BeforeEach
    void setup() {
        refunds.deleteAll();
        payments.deleteAll();
        states.deleteAll();
        events.deleteAll();
        when(clock.instant()).thenReturn(now);
        when(orders.getOwn(anyString(), eq(ORDER)))
                .thenReturn(
                        new OrderClient.OrderView(
                                ORDER,
                                "WAITING_PAYMENT",
                                new BigDecimal("3000.00"),
                                "VNPAY",
                                now.plusSeconds(900),
                                true));
        when(gateway.create(anyString(), eq(3000L), any())).thenAnswer(link(PaymentGatewayClient.Status.PENDING));
    }

    String token(String who, String role) {
        return "Bearer "
                + Jwts.builder()
                        .issuer("auth-service")
                        .subject(who)
                        .claim("roles", List.of(role))
                        .expiration(Date.from(Instant.now().plusSeconds(600)))
                        .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                        .compact();
    }

    Long create() throws Exception {
        var response =
                mvc.perform(
                                post("/api/payments")
                                        .header("Authorization", token("alice", "BUYER"))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                mapper.writeValueAsString(
                                                        Map.of("orderId", ORDER))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.amount").value(3000))
                        .andExpect(jsonPath("$.status").value("PENDING"))
                        .andReturn();
        return mapper.readTree(response.getResponse().getContentAsString()).path("id").asLong();
    }

    String ref(Long id) {
        return id + "T" + now.getEpochSecond();
    }

    static String payDate(Instant time) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                .withZone(ZoneId.of("Asia/Ho_Chi_Minh"))
                .format(time);
    }

    /** Tham số VNPay trả về (chưa ký) cho một giao dịch thành công của payment {@code id}. */
    Map<String, String> data(Long id) {
        var params = new TreeMap<String, String>();
        params.put("vnp_TmnCode", "TESTCODE");
        params.put("vnp_TxnRef", ref(id));
        params.put("vnp_Amount", "300000");
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_TransactionStatus", "00");
        params.put("vnp_TransactionNo", "bank-" + id);
        params.put("vnp_PayDate", payDate(now));
        return params;
    }

    Map<String, String> signed(Map<String, String> params) {
        var copy = new TreeMap<>(params);
        copy.put("vnp_SecureHash", signature.sign(params, properties.getHashSecret()));
        return copy;
    }

    org.springframework.test.web.servlet.ResultActions ipnCall(Map<String, String> params)
            throws Exception {
        var request = get("/api/payments/webhooks/vnpay");
        params.forEach(request::param);
        return mvc.perform(request).andExpect(status().isOk());
    }

    String ipnCode(Map<String, String> params) throws Exception {
        var body = ipnCall(params).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("RspCode").asString();
    }

    /** IPN đã ký đúng: 00 lần đầu, 02 khi VNPay gọi lại. */
    void ipnOk(Map<String, String> params) throws Exception {
        assertThat(ipnCode(signed(params))).isIn("00", "02");
    }

    org.springframework.test.web.servlet.ResultActions vnpayReturn(
            String who, Map<String, String> params) throws Exception {
        var request = get("/api/payments/vnpay/return").header("Authorization", token(who, "BUYER"));
        params.forEach(request::param);
        return mvc.perform(request);
    }

    org.mockito.stubbing.Answer<PaymentGatewayClient.Link> link(
            PaymentGatewayClient.Status status) {
        return invocation ->
                new PaymentGatewayClient.Link(
                        invocation.getArgument(0),
                        status == PaymentGatewayClient.Status.PENDING
                                ? "https://sandbox.vnpayment.vn/test"
                                : null,
                        null,
                        status);
    }

    @Test
    void authenticationAndOwnership() throws Exception {
        mvc.perform(get("/api/payments")).andExpect(status().isUnauthorized());
        Long id = create();
        mvc.perform(get("/api/payments/" + id).header("Authorization", token("bob", "BUYER")))
                .andExpect(status().isNotFound());
        mvc.perform(
                        get("/api/payments/admin/refunds")
                                .header("Authorization", token("alice", "BUYER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void duplicateCreateAndWebhookEmitOnce() throws Exception {
        Long id = create();
        assertThat(create()).isEqualTo(id);
        verify(gateway, times(1)).create(anyString(), anyLong(), any());
        var data = data(id);
        ipnOk(data);
        ipnOk(data);
        assertThat(payments.findById(id).orElseThrow().getStatus()).isEqualTo("SUCCEEDED");
        assertThat(events.findAll())
                .extracting(OutboxEvent::getEventType)
                .containsExactly("PaymentSucceeded");
    }

    @Test
    void failedResultMarksPaymentRetryableAndKeepsTheSameLink() throws Exception {
        Long id = create();
        var data = data(id);
        data.put("vnp_ResponseCode", "24");
        data.put("vnp_TransactionStatus", "02");
        assertThat(ipnCode(signed(data))).isEqualTo("00");
        assertThat(payments.findById(id).orElseThrow().getPaidAt()).isNull();
        assertThat(payments.findById(id).orElseThrow().getStatus()).isEqualTo("FAILED");
        assertThat(events.findAll())
                .extracting(OutboxEvent::getEventType)
                .containsExactly("PaymentFailed");
        assertThat(create()).isEqualTo(id);
    }

    @Test
    void responseCodeAloneIsNotSuccessWhenTransactionStatusFails() throws Exception {
        Long id = create();
        var data = data(id);
        data.put("vnp_TransactionStatus", "01");
        assertThat(ipnCode(signed(data))).isEqualTo("00");
        assertThat(payments.findById(id).orElseThrow().getPaidAt()).isNull();
        assertThat(payments.findById(id).orElseThrow().getStatus()).isEqualTo("FAILED");
    }

    @Test
    void ipnAnswersWithTheVnpayResponseCodes() throws Exception {
        Long id = create();
        var data = data(id);
        var forged = new TreeMap<>(data);
        forged.put("vnp_SecureHash", "0".repeat(128));
        assertThat(ipnCode(forged)).isEqualTo("97");
        assertThat(ipnCode(data)).isEqualTo("97");
        var tampered = new TreeMap<>(data);
        tampered.put("vnp_Amount", "100");
        assertThat(ipnCode(signed(tampered))).isEqualTo("04");
        tampered.put("vnp_Amount", "300050");
        assertThat(ipnCode(signed(tampered))).isEqualTo("04");
        var unknown = new TreeMap<>(data);
        unknown.put("vnp_TxnRef", "999999999T" + now.getEpochSecond());
        assertThat(ipnCode(signed(unknown))).isEqualTo("01");
        var foreignRef = new TreeMap<>(data);
        foreignRef.put("vnp_TxnRef", id + "T1");
        assertThat(ipnCode(signed(foreignRef))).isEqualTo("01");
        assertThat(events.count()).isZero();
        assertThat(ipnCode(signed(data))).isEqualTo("00");
        assertThat(ipnCode(signed(data))).isEqualTo("02");
        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void returnUrlConfirmsThePaymentForItsOwnerOnly() throws Exception {
        Long id = create();
        var data = signed(data(id));
        vnpayReturn("alice", data).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        // Gọi lại (hoặc IPN đến sau) không phát thêm sự kiện.
        vnpayReturn("alice", data).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertThat(ipnCode(data)).isEqualTo("02");
        assertThat(events.findAll())
                .extracting(OutboxEvent::getEventType)
                .containsExactly("PaymentSucceeded");
        vnpayReturn("bob", data).andExpect(status().isNotFound());
        mvc.perform(get("/api/payments/vnpay/return").param("vnp_TxnRef", ref(id)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnUrlRejectsBadSignatureAndMismatchedAmount() throws Exception {
        Long id = create();
        var forged = new TreeMap<>(data(id));
        forged.put("vnp_SecureHash", "0".repeat(128));
        vnpayReturn("alice", forged)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_SIGNATURE"));
        var tampered = data(id);
        tampered.put("vnp_Amount", "100");
        vnpayReturn("alice", signed(tampered))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_MISMATCH"));
        assertThat(payments.findById(id).orElseThrow().getPaidAt()).isNull();
        assertThat(events.count()).isZero();
    }

    @Test
    void expiryAndLateSuccessProduceRefundInsteadOfRevivingOrder() throws Exception {
        Long id = create();
        when(clock.instant()).thenReturn(now.plusSeconds(1021));
        store.expire(id);
        store.expire(id);
        var data = data(id);
        ipnOk(data);
        assertThat(events.findAll())
                .extracting(OutboxEvent::getEventType)
                .containsExactly("PaymentFailed");
        assertThat(refunds.findByPaymentId(id)).isPresent();
    }

    @Test
    void cancelSuccessRaceRefundsExactlyOnce() throws Exception {
        Long id = create();
        store.cancelled(ORDER);
        var data = data(id);
        ipnOk(data);
        store.cancelled(ORDER);
        assertThat(refunds.count()).isEqualTo(1);
        assertThat(events.count()).isZero();
    }

    @Test
    void cancellationWaitsForConcurrentWebhookAndPreservesItsCommittedPayment() throws Exception {
        Long id = create();
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var cancelling = new java.util.concurrent.CountDownLatch(1);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        var transaction =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try {
            var paid =
                    workers.submit(
                            () ->
                                    transaction.executeWithoutResult(
                                            tx -> {
                                                payments.lock(id).orElseThrow();
                                                locked.countDown();
                                                try {
                                                    if (!release.await(
                                                            5,
                                                            java.util.concurrent.TimeUnit.SECONDS))
                                                        throw new IllegalStateException(
                                                                "barrier timeout");
                                                } catch (InterruptedException ex) {
                                                    Thread.currentThread().interrupt();
                                                    throw new IllegalStateException(ex);
                                                }
                                                store.process(PaymentStore.VnpayResult.from(data(id)));
                                            }));
            assertThat(locked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var cancelled =
                    workers.submit(
                            () -> {
                                cancelling.countDown();
                                store.cancelled(ORDER);
                            });
            assertThat(cancelling.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            // A locked order lookup cannot return the pre-webhook managed instance.
            assertThatThrownBy(() -> cancelled.get(100, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            paid.get(5, java.util.concurrent.TimeUnit.SECONDS);
            cancelled.get(5, java.util.concurrent.TimeUnit.SECONDS);
            var saved = payments.findById(id).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo("SUCCEEDED");
            assertThat(saved.getPaidAt()).isNotNull();
            assertThat(saved.getReference()).isEqualTo("bank-" + id);
            assertThat(refunds.count()).isEqualTo(1);
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void signedWebhookRecoversLinkLostAfterProviderTimeout() throws Exception {
        when(gateway.create(anyString(), anyLong(), any()))
                .thenThrow(
                        com.vmarket.payment.exception.ApiException.unavailable(
                                "VNPAY_UNAVAILABLE", "timeout"));
        mvc.perform(
                        post("/api/payments")
                                .header("Authorization", token("alice", "BUYER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(Map.of("orderId", ORDER))))
                .andExpect(status().isServiceUnavailable());
        var id = payments.findByOrderId(ORDER).orElseThrow().getId();
        var data = data(id);
        ipnOk(data);
        assertThat(payments.findById(id).orElseThrow().getStatus()).isEqualTo("SUCCEEDED");
        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void recoveredLinkWithoutQrExposesCheckoutFallback() throws Exception {
        doReturn(new PaymentGatewayClient.Link("test-link", "https://pay.gateway.vn/test", null))
                .when(gateway)
                .create(anyString(), anyLong(), any());
        Long id = create();
        mvc.perform(get("/api/payments/" + id).header("Authorization", token("alice", "BUYER")))
                .andExpect(jsonPath("$.qrAvailable").value(false))
                .andExpect(jsonPath("$.checkoutUrl").value("https://pay.gateway.vn/test"));
    }

    @Test
    void buyerSeesPaymentOfOrderAndItsRefunds() throws Exception {
        Long id = create();
        mvc.perform(get("/api/payments/orders/" + ORDER).header("Authorization", token("alice", "BUYER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        mvc.perform(get("/api/payments/orders/" + ORDER).header("Authorization", token("bob", "BUYER")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/payments/" + id + "/refunds").header("Authorization", token("alice", "BUYER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        var data = data(id);
        ipnOk(data);
        store.cancelled(ORDER);
        mvc.perform(get("/api/payments/" + id + "/refunds").header("Authorization", token("alice", "BUYER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].amount").value(3000))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].confirmedBy").doesNotExist());
        mvc.perform(get("/api/payments/" + id + "/refunds").header("Authorization", token("bob", "BUYER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void manualRefundIsAdminOnlyAndAudited() throws Exception {
        Long id = create();
        var data = data(id);
        ipnOk(data);
        store.cancelled(ORDER);
        String refundId = refunds.findByPaymentId(id).orElseThrow().getId();
        String body = "{\"transferReference\":\"manual-bank-reference\"}";
        mvc.perform(
                        put("/api/payments/admin/refunds/" + refundId + "/confirm")
                                .header("Authorization", token("alice", "BUYER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isForbidden());
        for (int i = 0; i < 2; i++)
            mvc.perform(
                            put("/api/payments/admin/refunds/" + refundId + "/confirm")
                                    .header("Authorization", token("admin", "ADMIN"))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isOk());
        assertThat(refunds.findById(refundId).orElseThrow().getConfirmedBy()).isEqualTo("admin");
    }

    @Test
    void providerTimeoutRetriesTheSameCode() throws Exception {
        when(gateway.create(anyString(), anyLong(), any()))
                .thenThrow(
                        com.vmarket.payment.exception.ApiException.unavailable(
                                "VNPAY_UNAVAILABLE", "timeout"));
        mvc.perform(
                        post("/api/payments")
                                .header("Authorization", token("alice", "BUYER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(Map.of("orderId", ORDER))))
                .andExpect(status().isServiceUnavailable());
        var before = payments.findByOrderId(ORDER).orElseThrow();
        assertThat(before.getStatus()).isEqualTo("CREATED");
        doReturn(new PaymentGatewayClient.Link("test-link", "https://pay.gateway.vn/test", "test-qr"))
                .when(gateway)
                .create(anyString(), anyLong(), any());
        assertThat(create()).isEqualTo(before.getId());
    }

    @Test
    void stockMustBeReservedBeforeVnpay() throws Exception {
        when(orders.getOwn(anyString(), eq(ORDER)))
                .thenReturn(
                        new OrderClient.OrderView(
                                ORDER,
                                "WAITING_PAYMENT",
                                new BigDecimal("3000"),
                                "VNPAY",
                                now.plusSeconds(900),
                                false));
        mvc.perform(
                        post("/api/payments")
                                .header("Authorization", token("alice", "BUYER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(Map.of("orderId", ORDER))))
                .andExpect(status().isConflict());
        verifyNoInteractions(gateway);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"VNPAY", "COD"})
    void cancellationAfterOrderSnapshotPreventsFirstReservation(String method) throws Exception {
        var snapshot =
                new OrderClient.OrderView(
                        ORDER,
                        method.equals("VNPAY") ? "WAITING_PAYMENT" : "PENDING",
                        new BigDecimal("3000"),
                        method,
                        method.equals("VNPAY") ? now.plusSeconds(900) : null,
                        true);
        when(orders.getOwn(anyString(), eq(ORDER)))
                .thenAnswer(
                        invocation -> {
                            // The cancellation event commits after the snapshot was read, before
                            // reserve locks it.
                            store.cancelled(ORDER);
                            return snapshot;
                        });
        mvc.perform(
                        post("/api/payments")
                                .header("Authorization", token("alice", "BUYER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(Map.of("orderId", ORDER))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_PAYABLE"));
        assertThat(payments.count()).isZero();
        assertThat(states.findById(ORDER).orElseThrow().isCancelled()).isTrue();
        verifyNoInteractions(gateway);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"CANCELLED", "EXPIRED"})
    void recoveredTerminalProviderLinkHonorsSettlementGraceAndFailsOrderOnce(String state)
            throws Exception {
        doAnswer(link(PaymentGatewayClient.Status.valueOf(state)))
                .when(gateway)
                .create(anyString(), anyLong(), any());
        for (int i = 0; i < 2; i++)
            mvc.perform(
                            post("/api/payments")
                                    .header("Authorization", token("alice", "BUYER"))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(mapper.writeValueAsString(Map.of("orderId", ORDER))))
                    .andExpect(status().isOk())
                    .andExpect(
                            jsonPath("$.status")
                                    .value("EXPIRED".equals(state) ? "AWAITING_WEBHOOK" : state))
                    .andExpect(jsonPath("$.checkoutUrl").isEmpty())
                    .andExpect(jsonPath("$.qrAvailable").value(false));
        if ("EXPIRED".equals(state)) {
            assertThat(events.count()).isZero();
            when(clock.instant()).thenReturn(now.plusSeconds(1021));
            Long id = payments.findByOrderId(ORDER).orElseThrow().getId();
            store.expire(id);
            store.expire(id);
        }
        assertThat(payments.findByOrderId(ORDER).orElseThrow().getStatus()).isEqualTo(state);
        assertThat(events.findAll())
                .extracting(OutboxEvent::getEventType)
                .containsExactly("PaymentFailed");
        verify(gateway, times(1)).create(anyString(), anyLong(), any());
    }

    @Test
    void recoveredExpiredLinkAcceptsTimelyBankPaymentDuringSettlementGrace() throws Exception {
        doAnswer(link(PaymentGatewayClient.Status.EXPIRED))
                .when(gateway)
                .create(anyString(), anyLong(), any());
        mvc.perform(
                        post("/api/payments")
                                .header("Authorization", token("alice", "BUYER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(Map.of("orderId", ORDER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_WEBHOOK"));
        Long id = payments.findByOrderId(ORDER).orElseThrow().getId();
        when(clock.instant()).thenReturn(now.plusSeconds(905));
        var bankData = data(id);
        bankData.put("vnp_PayDate", payDate(now.plusSeconds(839)));
        ipnOk(bankData);
        assertThat(payments.findById(id).orElseThrow().getStatus()).isEqualTo("SUCCEEDED");
        assertThat(refunds.count()).isZero();
        assertThat(events.findAll())
                .extracting(OutboxEvent::getEventType)
                .containsExactly("PaymentSucceeded");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"PAID", "PROCESSING"})
    void recoveredPaidProviderLinkRequiresWebhookBeforeSuccess(String state) throws Exception {
        doAnswer(link(PaymentGatewayClient.Status.valueOf(state)))
                .when(gateway)
                .create(anyString(), anyLong(), any());
        for (int i = 0; i < 2; i++)
            mvc.perform(
                            post("/api/payments")
                                    .header("Authorization", token("alice", "BUYER"))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(mapper.writeValueAsString(Map.of("orderId", ORDER))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("RECONCILING"))
                    .andExpect(jsonPath("$.reconciliationRequired").value(true))
                    .andExpect(jsonPath("$.checkoutUrl").isEmpty());
        var p = payments.findByOrderId(ORDER).orElseThrow();
        assertThat(p.getPaidAt()).isNull();
        assertThat(events.count()).isZero();
        verify(gateway, times(1)).create(anyString(), anyLong(), any());
        mvc.perform(
                        get("/api/payments/admin/reconciliations")
                                .header("Authorization", token("admin", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        var data = data(p.getId());
        ipnOk(data);
        ipnOk(data);
        assertThat(payments.findById(p.getId()).orElseThrow().isReconciliationRequired()).isFalse();
        assertThat(events.findAll())
                .extracting(OutboxEvent::getEventType)
                .containsExactly("PaymentSucceeded");
    }

    @Test
    void timelyBankPaymentSurvivesWebhookArrivalAfterTheOrderDeadline() throws Exception {
        Long id = create();
        verify(gateway).create(ref(id), 3000L, now.plusSeconds(840));
        when(clock.instant()).thenReturn(now.plusSeconds(905));
        store.expire(id);
        assertThat(events.count()).isZero();
        var payload = data(id);
        payload.put("vnp_PayDate", "20261006221459");
        ipnOk(payload);
        assertThat(payments.findById(id).orElseThrow().getPaidAt()).isEqualTo(now.plusSeconds(899));
        assertThat(refunds.count()).isZero();
        assertThat(events.findAll())
                .extracting(OutboxEvent::getEventType)
                .containsExactly("PaymentSucceeded");
    }

    @Test
    void actualLateBankPaymentFailsTheOrderAndQueuesRefundDuringGrace() throws Exception {
        Long id = create();
        when(clock.instant()).thenReturn(now.plusSeconds(905));
        var payload = data(id);
        payload.put("vnp_PayDate", payDate(now.plusSeconds(901)));
        ipnOk(payload);
        assertThat(refunds.count()).isEqualTo(1);
        assertThat(events.findAll())
                .extracting(OutboxEvent::getEventType)
                .containsExactly("PaymentFailed");
    }

    @Test
    void invalidBankTimestampAndOversizedReferenceAreRejectedBeforePersistence() throws Exception {
        Long id = create();
        var payload = data(id);
        payload.put("vnp_PayDate", "not-a-date");
        assertThat(ipnCode(signed(payload))).isEqualTo("99");
        payload = data(id);
        payload.put("vnp_TransactionNo", "x".repeat(129));
        assertThat(ipnCode(signed(payload))).isEqualTo("99");
        payload = data(id);
        payload.put("vnp_TransactionNo", "0");
        assertThat(ipnCode(signed(payload))).isEqualTo("99");
        assertThat(payments.findById(id).orElseThrow().getPaidAt()).isNull();
        assertThat(events.count()).isZero();
    }

    @Test
    void codAndVnpayCanUseTheSameReferenceInTheirOwnNamespaces() throws Exception {
        Long id = create();
        var payload = data(id);
        payload.put("vnp_TransactionNo", "shared-reference");
        ipnOk(payload);
        store.codCollected(
                new com.vmarket.events.CodCollected(
                        "02" + "0".repeat(24), "alice", 3000L, "shared-reference"));
        assertThat(payments.findAll())
                .hasSize(2)
                .allSatisfy(p -> assertThat(p.getReference()).isEqualTo("shared-reference"));
    }

    @Test
    void webhookCanCommitWhileProviderCreateIsInFlightAndItsSuccessIsPreserved() throws Exception {
        doAnswer(
                        invocation -> {
                            assertThat(
                                            org.springframework.transaction.support
                                                    .TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            Long id = PaymentStore.paymentId(invocation.getArgument(0));
                            store.process(PaymentStore.VnpayResult.from(data(id)));
                            return new PaymentGatewayClient.Link(
                                    invocation.getArgument(0),
                                    "https://sandbox.vnpayment.vn/test",
                                    null);
                        })
                .when(gateway)
                .create(anyString(), anyLong(), any());
        mvc.perform(
                        post("/api/payments")
                                .header("Authorization", token("alice", "BUYER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(Map.of("orderId", ORDER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void cancellationCanCommitBeforeProviderCreateReturns() throws Exception {
        Long id =
                store.reserve(
                        "alice",
                        new OrderClient.OrderView(
                                ORDER,
                                "WAITING_PAYMENT",
                                new BigDecimal("3000"),
                                "VNPAY",
                                now.plusSeconds(900),
                                true));
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        doAnswer(
                        invocation -> {
                            assertThat(
                                            org.springframework.transaction.support
                                                    .TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            started.countDown();
                            assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                    .isTrue();
                            return new PaymentGatewayClient.Link(
                                    invocation.getArgument(0),
                                    "https://sandbox.vnpayment.vn/test",
                                    null);
                        })
                .when(gateway)
                .create(anyString(), anyLong(), any());
        try {
            var creating = pool.submit(() -> store.createLink(id, gateway));
            assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            pool.submit(() -> store.cancelled(ORDER)).get(2, java.util.concurrent.TimeUnit.SECONDS);
            release.countDown();
            assertThat(creating.get(5, java.util.concurrent.TimeUnit.SECONDS).status())
                    .isEqualTo("CANCELLED");
            var saved = payments.findById(id).orElseThrow();
            assertThat(saved.isCancelPending()).isTrue();
            assertThat(saved.getCheckoutUrl()).isNull();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void cancellationRetryLimitMovesThePaymentToReconciliation() throws Exception {
        Long id = create();
        when(clock.instant()).thenReturn(now.plusSeconds(1021));
        store.expire(id);
        doAnswer(
                        invocation -> {
                            assertThat(
                                            org.springframework.transaction.support
                                                    .TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            throw com.vmarket.payment.exception.ApiException.unavailable(
                                    "VNPAY_UNAVAILABLE", "offline");
                        })
                .when(gateway)
                .cancel(ref(id));
        for (int i = 0; i < 10; i++) {
            store.cancelRemote(id, gateway);
            when(clock.instant())
                    .thenReturn(payments.findById(id).orElseThrow().getNextCancelAttemptAt());
        }
        var saved = payments.findById(id).orElseThrow();
        assertThat(saved.isCancelPending()).isFalse();
        assertThat(saved.isReconciliationRequired()).isTrue();
        assertThat(saved.getCancelAttempts()).isEqualTo(8);
        verify(gateway, times(8)).cancel(ref(id));
    }

    @Test
    void aStaleCancellationResultCannotClearTheRetryForANewlyCreatedLink() throws Exception {
        Long id =
                store.reserve(
                        "alice",
                        new OrderClient.OrderView(
                                ORDER,
                                "WAITING_PAYMENT",
                                new BigDecimal("3000"),
                                "VNPAY",
                                now.plusSeconds(900),
                                true));
        var createStarted = new java.util.concurrent.CountDownLatch(1);
        var finishCreate = new java.util.concurrent.CountDownLatch(1);
        var cancelStarted = new java.util.concurrent.CountDownLatch(1);
        var finishCancel = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        doAnswer(
                        invocation -> {
                            createStarted.countDown();
                            assertThat(finishCreate.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                    .isTrue();
                            return new PaymentGatewayClient.Link(
                                    invocation.getArgument(0),
                                    "https://sandbox.vnpayment.vn/test",
                                    null);
                        })
                .when(gateway)
                .create(anyString(), anyLong(), any());
        doAnswer(
                        invocation -> {
                            cancelStarted.countDown();
                            assertThat(finishCancel.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                    .isTrue();
                            return null;
                        })
                .when(gateway)
                .cancel(ref(id));
        try {
            var creating = pool.submit(() -> store.createLink(id, gateway));
            assertThat(createStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            store.cancelled(ORDER);
            var cancelling = pool.submit(() -> store.cancelRemote(id, gateway));
            assertThat(cancelStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            finishCreate.countDown();
            creating.get(5, java.util.concurrent.TimeUnit.SECONDS);
            finishCancel.countDown();
            cancelling.get(5, java.util.concurrent.TimeUnit.SECONDS);
            var saved = payments.findById(id).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo("CANCELLED");
            assertThat(saved.isCancelPending()).isTrue();
            assertThat(saved.getPaymentLinkId()).isEqualTo(ref(id));
        } finally {
            finishCreate.countDown();
            finishCancel.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void successfulCancellationStopsRetrying() throws Exception {
        Long id =
                store.reserve(
                        "alice",
                        new OrderClient.OrderView(
                                ORDER,
                                "WAITING_PAYMENT",
                                new BigDecimal("3000"),
                                "VNPAY",
                                now.plusSeconds(900),
                                true));
        store.cancelled(ORDER);
        store.cancelRemote(id, gateway);
        store.cancelRemote(id, gateway);
        assertThat(payments.findById(id).orElseThrow().isCancelPending()).isFalse();
        verify(gateway, times(1)).cancel(ref(id));
    }

    @Test
    void oneFailedOutboxRowCannotRollBackOtherConfirmedRows() {
        var outbox = new Outbox(events, mapper);
        outbox.add("Good", Map.of("orderId", "good-a"));
        outbox.add("Bad", Map.of("orderId", "bad"));
        outbox.add("Good", Map.of("orderId", "good-b"));
        doThrow(new IllegalStateException("poison")).when(publisher).publish(eq("Bad"), any());
        dispatcher.dispatch();
        assertThat(events.findAll())
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.getEventType()).isEqualTo("Bad");
                            assertThat(event.getRetryAttempts()).isEqualTo(1);
                        });
        verify(publisher, times(2)).publish(eq("Good"), any());
    }

    @Test
    void codDoesNotContactProvider() throws Exception {
        when(orders.getOwn(anyString(), eq(ORDER)))
                .thenReturn(
                        new OrderClient.OrderView(
                                ORDER, "PENDING", new BigDecimal("3000"), "COD", null, false));
        mvc.perform(
                        post("/api/payments")
                                .header("Authorization", token("alice", "BUYER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(Map.of("orderId", ORDER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COD_PENDING"));
        verifyNoInteractions(gateway);
    }

    @Test
    void outboxSurvivesBrokerOutage() throws Exception {
        Long id = create();
        var data = data(id);
        ipnOk(data);
        doThrow(new org.springframework.amqp.AmqpException("offline"))
                .when(publisher)
                .publish(anyString(), any());
        dispatcher.dispatch();
        assertThat(events.count()).isEqualTo(1);
        assertThat(events.findAll().get(0).getRetryAttempts()).isEqualTo(1);
        doNothing().when(publisher).publish(anyString(), any());
        var retained = events.findAll().get(0);
        retained.setNextAttemptAt(Instant.EPOCH);
        events.saveAndFlush(retained);
        dispatcher.dispatch();
        assertThat(events.count()).isZero();
    }

    @Test
    void codCollectionWithoutBuyerPaymentRequestIsDuplicateSafe() {
        var collected = new com.vmarket.events.CodCollected(ORDER, "alice", 3000L, "delivery-1");
        store.codCollected(collected);
        store.codCollected(collected);
        var payment = payments.findByOrderId(ORDER).orElseThrow();
        assertThat(payment.getMethod()).isEqualTo("COD");
        assertThat(payment.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(payments.count()).isEqualTo(1);
        assertThat(events.count()).isZero();
        verifyNoInteractions(gateway);
    }

    @Test
    void cancellationBeforeCodCollectionWithoutPaymentStillCreatesRefund() {
        store.cancelled(ORDER);
        store.codCollected(
                new com.vmarket.events.CodCollected(ORDER, "alice", 3000L, "delivery-1"));
        assertThat(refunds.count()).isEqualTo(1);
    }

    @Test
    void cancellationAfterCodCollectionWithoutPaymentCreatesRefund() {
        store.codCollected(
                new com.vmarket.events.CodCollected(ORDER, "alice", 3000L, "delivery-1"));
        store.cancelled(ORDER);
        store.cancelled(ORDER);
        assertThat(refunds.count()).isEqualTo(1);
    }

    @Test
    void concurrentFirstCodAndCancellationPreserveRefund() throws Exception {
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var paid =
                    workers.submit(
                            () -> {
                                start.await();
                                store.codCollected(
                                        new com.vmarket.events.CodCollected(
                                                ORDER, "alice", 3000L, "delivery-1"));
                                return null;
                            });
            var cancel =
                    workers.submit(
                            () -> {
                                start.await();
                                store.cancelled(ORDER);
                                return null;
                            });
            start.countDown();
            paid.get(10, java.util.concurrent.TimeUnit.SECONDS);
            cancel.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(payments.count()).isEqualTo(1);
            assertThat(refunds.count()).isEqualTo(1);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void failingCancellationBatchDoesNotStarveTheNextPayment() {
        var ids = new ArrayList<Long>();
        for (int i = 0; i < 101; i++) {
            var p = new PaymentTransaction();
            p.setOrderId(String.format("%026d", i));
            p.setBuyerId("alice");
            p.setAmount(3000L);
            p.setMethod("VNPAY");
            p.setStatus("EXPIRED");
            p.setCreatedAt(now);
            p.setCancelPending(true);
            p.setNextCancelAttemptAt(now);
            ids.add(payments.saveAndFlush(p).getId());
        }
        doThrow(
                        com.vmarket.payment.exception.ApiException.unavailable(
                                "VNPAY_UNAVAILABLE", "offline"))
                .when(gateway)
                .cancel(anyString());
        doNothing().when(gateway).cancel(ref(ids.get(100)));
        var job = new PaymentExpiryJob(payments, store, gateway, clock);
        job.run();
        job.run();
        verify(gateway).cancel(ref(ids.get(100)));
        assertThat(payments.findById(ids.get(100)).orElseThrow().isCancelPending()).isFalse();
        assertThat(payments.findById(ids.get(0)).orElseThrow().getCancelAttempts()).isEqualTo(1);
    }

    @Test
    void paymentArrivingAfterExpiryAndCancellationIsRefunded() throws Exception {
        Long id = create();
        when(clock.instant()).thenReturn(now.plusSeconds(1021));
        store.expire(id);
        store.cancelRemote(id, gateway);
        assertThat(payments.findById(id).orElseThrow().isCancelPending()).isFalse();
        verify(gateway, times(1)).cancel(ref(id));
        mvc.perform(
                        get("/api/payments/admin/reconciliations")
                                .header("Authorization", token("alice", "BUYER")))
                .andExpect(status().isForbidden());
        var data = data(id);
        ipnOk(data);
        assertThat(payments.findById(id).orElseThrow().isReconciliationRequired()).isFalse();
        assertThat(refunds.count()).isEqualTo(1);
    }
}
