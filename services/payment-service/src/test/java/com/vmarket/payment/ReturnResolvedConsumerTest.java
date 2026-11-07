package com.vmarket.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.events.EventEnvelope;
import com.vmarket.events.ReturnResolved;
import com.vmarket.payment.entity.PaymentTransaction;
import com.vmarket.payment.entity.RefundRequest;
import com.vmarket.payment.event.ReturnResolvedConsumer;
import com.vmarket.payment.repository.PaymentRepository;
import com.vmarket.payment.repository.RefundRepository;

@SpringBootTest
@Transactional
class ReturnResolvedConsumerTest {

    @Autowired private ReturnResolvedConsumer consumer;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private RefundRepository refundRepository;
    @Autowired private JdbcTemplate jdbc;

    private static final String ORDER_ID = "01M6TESTORDER000000000001";
    private static final String BUYER_ID = "01M6TESTBUYER000000000001";

    private Long paymentId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM refund_requests");
        jdbc.update("DELETE FROM payment_transactions");

        PaymentTransaction tx = new PaymentTransaction();
        tx.setOrderId(ORDER_ID);
        tx.setBuyerId(BUYER_ID);
        tx.setAmount(500000L); // 500,000 VND
        tx.setMethod("PAYOS");
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
}

