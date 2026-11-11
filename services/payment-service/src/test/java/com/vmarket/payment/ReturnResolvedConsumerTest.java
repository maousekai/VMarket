package com.vmarket.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.events.CodCollected;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.ReturnResolved;
import com.vmarket.payment.entity.PaymentTransaction;
import com.vmarket.payment.entity.RefundRequest;
import com.vmarket.payment.event.ReturnResolvedConsumer;
import com.vmarket.payment.repository.PaymentRepository;
import com.vmarket.payment.repository.RefundRepository;
import com.vmarket.payment.service.PaymentStore;

@SpringBootTest
@Transactional
class ReturnResolvedConsumerTest {

    @Autowired private ReturnResolvedConsumer consumer;
    @Autowired private PaymentStore paymentStore;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private RefundRepository refundRepository;
    @Autowired private JdbcTemplate jdbc;

    private static final String ORDER_ID = "01M6TESTORDER000000000001";
    private static final String BUYER_ID = "01M6TESTBUYER000000000001";

    private Long paymentId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM pending_return_refunds");
        jdbc.update("DELETE FROM refund_requests");
        jdbc.update("DELETE FROM payment_transactions");

        PaymentTransaction tx = new PaymentTransaction();
        tx.setOrderId(ORDER_ID);
        tx.setBuyerId(BUYER_ID);
        tx.setAmount(500000L); // 500,000 VND
        tx.setMethod("VNPAY");
        tx.setStatus("SUCCEEDED");
        tx.setCreatedAt(Instant.parse("2026-11-01T10:00:00Z"));
        tx.setPaidAt(Instant.parse("2026-11-01T10:05:00Z"));
        paymentId = paymentRepository.saveAndFlush(tx).getId();
    }

    @Test
    void returnResolved_createsRefundRequest_withCorrectAmount() {
        ReturnResolved event = new ReturnResolved(
                "RET-001",
                ORDER_ID,
                BigDecimal.valueOf(200000),
                true,
                List.of());

        consumer.handle(event, new EventEnvelope("evt-1", "ReturnResolved", 0, null));

        RefundRequest refund = refundRepository.findByReturnId("RET-001").orElse(null);
        assertThat(refund).isNotNull();
        assertThat(refund.getPaymentId()).isEqualTo(paymentId);
        assertThat(refund.getReturnId()).isEqualTo("RET-001");
        assertThat(refund.getAmount()).isEqualTo(200000L);
        assertThat(refund.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void returnResolved_replayEvent_isIdempotent() {
        ReturnResolved event = new ReturnResolved(
                "RET-001",
                ORDER_ID,
                BigDecimal.valueOf(200000),
                true,
                List.of());

        // First handle
        consumer.handle(event, new EventEnvelope("evt-1", "ReturnResolved", 0, null));
        // Second handle (replay)
        consumer.handle(event, new EventEnvelope("evt-2", "ReturnResolved", 0, null));

        List<RefundRequest> allRefunds = refundRepository.findAllByPaymentId(paymentId);
        assertThat(allRefunds).hasSize(1);
        assertThat(allRefunds.get(0).getReturnId()).isEqualTo("RET-001");
    }

    @Test
    void returnResolved_supportsMultiplePartialReturns_cappedAtTotalPaid() {
        // Return 1: 300,000 VND
        consumer.handle(
                new ReturnResolved("RET-001", ORDER_ID, BigDecimal.valueOf(300000), true, List.of()),
                new EventEnvelope("evt-1", "ReturnResolved", 0, null));

        // Return 2: 300,000 VND (should be capped at 200,000 VND because total order amount is 500,000)
        consumer.handle(
                new ReturnResolved("RET-002", ORDER_ID, BigDecimal.valueOf(300000), true, List.of()),
                new EventEnvelope("evt-2", "ReturnResolved", 0, null));

        List<RefundRequest> allRefunds = refundRepository.findAllByPaymentId(paymentId);
        assertThat(allRefunds).hasSize(2);

        RefundRequest r1 = refundRepository.findByReturnId("RET-001").orElseThrow();
        RefundRequest r2 = refundRepository.findByReturnId("RET-002").orElseThrow();

        assertThat(r1.getAmount()).isEqualTo(300000L);
        assertThat(r2.getAmount()).isEqualTo(200000L); // capped remaining
    }

    // --- R3-01 Tests: Out-of-order ReturnResolved vs CodCollected ---

    @Test
    void returnResolved_arrivesBeforeCodCollected_whenPaymentPending_createsRefundOnceCodCollected() {
        String codOrderId = "01M6TESTCODORDER000000001";
        PaymentTransaction codTx = new PaymentTransaction();
        codTx.setOrderId(codOrderId);
        codTx.setBuyerId(BUYER_ID);
        codTx.setAmount(400000L);
        codTx.setMethod("COD");
        codTx.setStatus("PENDING");
        codTx.setCreatedAt(Instant.parse("2026-11-02T10:00:00Z"));
        codTx.setPaidAt(null); // Not collected yet
        Long codPaymentId = paymentRepository.saveAndFlush(codTx).getId();

        // 1. ReturnResolved arrives BEFORE CodCollected
        ReturnResolved event = new ReturnResolved(
                "RET-COD-001",
                codOrderId,
                BigDecimal.valueOf(150000),
                true,
                List.of());
        consumer.handle(event, new EventEnvelope("evt-cod-1", "ReturnResolved", 0, null));

        // Chưa có refund ngay vì COD chưa thu tiền
        assertThat(refundRepository.findByReturnId("RET-COD-001")).isEmpty();

        // 2. CodCollected arrives later
        paymentStore.codCollected(new CodCollected(codOrderId, BUYER_ID, 400000L, "COD-REF-001"));

        // Sau khi COD thu tiền, pending return refund được tự động kích hoạt
        RefundRequest refund = refundRepository.findByReturnId("RET-COD-001").orElse(null);
        assertThat(refund).isNotNull();
        assertThat(refund.getPaymentId()).isEqualTo(codPaymentId);
        assertThat(refund.getAmount()).isEqualTo(150000L);
        assertThat(refund.getReason()).contains("RET-COD-001");
    }

    @Test
    void returnResolved_arrivesBeforePaymentRecordExists_createsRefundOnceCodCollected() {
        String codOrderId = "01M6TESTCODORDER000000002";

        // 1. ReturnResolved arrives when payment record doesn't exist yet
        ReturnResolved event = new ReturnResolved(
                "RET-COD-002",
                codOrderId,
                BigDecimal.valueOf(250000),
                true,
                List.of());
        consumer.handle(event, new EventEnvelope("evt-cod-2", "ReturnResolved", 0, null));

        // 2. Later, payment is created (COD pending)
        PaymentTransaction codTx = new PaymentTransaction();
        codTx.setOrderId(codOrderId);
        codTx.setBuyerId(BUYER_ID);
        codTx.setAmount(600000L);
        codTx.setMethod("COD");
        codTx.setStatus("PENDING");
        codTx.setCreatedAt(Instant.parse("2026-11-02T11:00:00Z"));
        codTx.setPaidAt(null);
        Long codPaymentId = paymentRepository.saveAndFlush(codTx).getId();

        // 3. Later, CodCollected arrives
        paymentStore.codCollected(new CodCollected(codOrderId, BUYER_ID, 600000L, "COD-REF-002"));

        RefundRequest refund = refundRepository.findByReturnId("RET-COD-002").orElse(null);
        assertThat(refund).isNotNull();
        assertThat(refund.getPaymentId()).isEqualTo(codPaymentId);
        assertThat(refund.getAmount()).isEqualTo(250000L);
    }

    // --- R3-02 Tests: Partial returns followed by cancellation / Admin full refund ---

    @Test
    void partialReturns_thenCancelled_refundsRemainingAmountWithoutSingleResultError() {
        // Payment amount: 500,000 VND
        // Return 1: 150,000 VND
        consumer.handle(
                new ReturnResolved("RET-P1", ORDER_ID, BigDecimal.valueOf(150000), true, List.of()),
                new EventEnvelope("evt-p1", "ReturnResolved", 0, null));

        // Return 2: 150,000 VND
        consumer.handle(
                new ReturnResolved("RET-P2", ORDER_ID, BigDecimal.valueOf(150000), true, List.of()),
                new EventEnvelope("evt-p2", "ReturnResolved", 0, null));

        // Hiện tại đã có 2 partial refunds (150k + 150k = 300k)
        assertThat(refundRepository.findAllByPaymentId(paymentId)).hasSize(2);

        // Đơn bị huỷ -> cancelled() phải tạo refund cho phần còn lại (500k - 300k = 200k),
        // và KHÔNG được ném IncorrectResultSizeDataAccessException
        paymentStore.cancelled(ORDER_ID);

        List<RefundRequest> allRefunds = refundRepository.findAllByPaymentId(paymentId);
        assertThat(allRefunds).hasSize(3);

        long totalAmount = allRefunds.stream().mapToLong(RefundRequest::getAmount).sum();
        assertThat(totalAmount).isEqualTo(500000L);

        RefundRequest cancelRefund = refundRepository.findByReturnId("CANCELLATION_" + paymentId).orElseThrow();
        assertThat(cancelRefund.getAmount()).isEqualTo(200000L);
    }

    @Test
    void partialReturns_thenAdminRequestRefund_refundsRemainingAmountAndReplayIsIdempotent() {
        // Payment amount: 500,000 VND
        // Return: 200,000 VND
        consumer.handle(
                new ReturnResolved("RET-P3", ORDER_ID, BigDecimal.valueOf(200000), true, List.of()),
                new EventEnvelope("evt-p3", "ReturnResolved", 0, null));

        // Admin gọi requestRefund -> hoàn phần còn lại 300,000 VND
        RefundRequest adminRefund = paymentStore.requestRefund(paymentId, "Admin full refund");
        assertThat(adminRefund.getAmount()).isEqualTo(300000L);
        assertThat(adminRefund.getReturnId()).isEqualTo("CANCELLATION_" + paymentId);

        // Replay requestRefund lần nữa -> idempotent, không sinh thêm bản ghi
        RefundRequest replayed = paymentStore.requestRefund(paymentId, "Admin full refund");
        assertThat(replayed.getId()).isEqualTo(adminRefund.getId());

        List<RefundRequest> allRefunds = refundRepository.findAllByPaymentId(paymentId);
        assertThat(allRefunds).hasSize(2);
        long totalAmount = allRefunds.stream().mapToLong(RefundRequest::getAmount).sum();
        assertThat(totalAmount).isEqualTo(500000L);
    }
}
