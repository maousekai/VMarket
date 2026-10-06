package com.vmarket.order;
import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.vmarket.order.entity.*;
import com.vmarket.order.repository.OrderRepository;
import com.vmarket.order.event.*;
import com.vmarket.order.service.OrderPaymentLifecycle;
@SpringBootTest
class OrderPaymentLifecycleTest {
    @Autowired OrderRepository orders;
    @Autowired OutboxRepository events;
    @Autowired OrderPaymentLifecycle lifecycle;
    @BeforeEach void clean() { orders.deleteAll(); events.deleteAll(); }
    @AfterEach void cleanup() { orders.deleteAll(); events.deleteAll(); }
    Order create() {
        var order = new Order(); order.setUserId("alice"); order.setStatus(OrderStatus.WAITING_PAYMENT);
        order.setPaymentMethod("PAYOS"); order.setPaymentExpiresAt(Instant.now().plusSeconds(900));
        order.setRecipientName("Alice"); order.setPhone("0901234567"); order.setProvince("DN");
        order.setDistrict("HC"); order.setWard("TT"); order.setStreetAddress("Address"); order.setTotalAmount(new BigDecimal("3000"));
        return orders.saveAndFlush(order);
    }
    @Test void paymentSuccessTransitionsOnce() {
        var order = create(); lifecycle.paid(order.getId()); lifecycle.paid(order.getId());
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(events.count()).isEqualTo(1);
    }
    @Test void expiredOrderCancelsOnceAndQueuesStockRelease() {
        var order = create(); var time = order.getPaymentExpiresAt().plusSeconds(1);
        lifecycle.expire(order.getId(), time); lifecycle.expire(order.getId(), time);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(events.findAll()).singleElement().satisfies(e -> assertThat(e.getPayload()).contains("CANCELLED"));
    }
    @Test void successRacingCancellationRequestsRefundReconciliation() {
        var order = create(); lifecycle.fail(order.getId()); lifecycle.paid(order.getId());
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(events.count()).isEqualTo(2);
    }
    @Test void paymentFailureCannotCancelPaidOrder() {
        var order = create(); lifecycle.paid(order.getId()); lifecycle.fail(order.getId());
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
    }
    @Test void stockFailureCancelsWaitingOrder() {
        var order = create(); lifecycle.stockFailed(order.getId());
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }
}
