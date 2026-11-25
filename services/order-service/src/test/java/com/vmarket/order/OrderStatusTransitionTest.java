package com.vmarket.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vmarket.events.DeliveryStatusChanged;
import com.vmarket.order.delivery.OrderDeliveryBridge;
import com.vmarket.order.entity.Order;
import com.vmarket.order.entity.OrderStatus;
import com.vmarket.order.entity.PaymentStatus;
import com.vmarket.order.event.OutboxRepository;
import com.vmarket.order.exception.IllegalOrderTransitionException;
import com.vmarket.order.repository.OrderRepository;
import com.vmarket.order.service.AdminOrderService;
import com.vmarket.order.service.OrderPaymentLifecycle;
import com.vmarket.order.service.OrderService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * PBL6-63: mỗi cạnh của bảng chuyển trạng thái được chạy qua đúng entry point thật của nó
 * (HTTP, consumer sự kiện hoặc service), kèm hệ quả trên trục thanh toán.
 */
@AutoConfigureMockMvc
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:order-transitions;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.show-sql=false"
        })
class OrderStatusTransitionTest {

    private static final String SECRET = "test-only-secret-0123456789-0123456789-0123456789";
    private static final String BUYER = "01JBQ9USER000000000ALICE01";
    private static final String ADMIN = "01JBQ9USER00000000000ADMIN";

    @Autowired MockMvc mvc;
    @Autowired OrderRepository orders;
    @Autowired OutboxRepository events;
    @Autowired OrderPaymentLifecycle payments;
    @Autowired OrderService buyer;
    @Autowired AdminOrderService admin;
    @Autowired OrderDeliveryBridge delivery;

    @BeforeEach
    @AfterEach
    void clean() {
        events.deleteAll();
        orders.deleteAll();
    }

    private String order(OrderStatus status, String paymentMethod, PaymentStatus payment) {
        var order = new Order();
        order.setUserId(BUYER);
        order.setStatus(OrderStatus.PENDING);
        order.setPaymentMethod(paymentMethod);
        order.setStockReserved(true);
        order.setRecipientName("Alice");
        order.setPhone("0901234567");
        order.setProvince("DN");
        order.setDistrict("HC");
        order.setWard("TT");
        order.setStreetAddress("1 Bach Dang");
        order.setTotalAmount(new BigDecimal("100000"));
        TestOrders.forceStatus(order, status);
        if (payment != PaymentStatus.UNPAID) order.markPaid();
        if (payment == PaymentStatus.REFUNDED) order.markRefunded();
        return orders.saveAndFlush(order).getId();
    }

    private void assertState(String id, OrderStatus status, PaymentStatus payment) {
        var saved = orders.findById(id).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(status);
        assertThat(saved.getPaymentStatus()).isEqualTo(payment);
    }

    private void assertOneStatusEvent(OrderStatus from, OrderStatus to) {
        assertThat(events.findAll())
                .singleElement()
                .satisfies(
                        e -> assertThat(e.getPayload())
                                .contains("\"previousStatus\":\"" + from + "\"")
                                .contains("\"status\":\"" + to + "\""));
    }

    private void deliveryEvent(String id, String status) {
        delivery.apply(new DeliveryStatusChanged(id, status, 1));
    }

    // ---- WAITING_PAYMENT ----

    @Test
    void waitingPaymentToPending_onPaymentSucceeded_marksPaid() {
        String id = order(OrderStatus.WAITING_PAYMENT, "VNPAY", PaymentStatus.UNPAID);
        payments.paid(id);
        assertState(id, OrderStatus.PENDING, PaymentStatus.PAID);
        assertOneStatusEvent(OrderStatus.WAITING_PAYMENT, OrderStatus.PENDING);
    }

    @Test
    void waitingPaymentToCancelled_onPaymentFailed() {
        String id = order(OrderStatus.WAITING_PAYMENT, "VNPAY", PaymentStatus.UNPAID);
        payments.fail(id);
        assertState(id, OrderStatus.CANCELLED, PaymentStatus.UNPAID);
        assertOneStatusEvent(OrderStatus.WAITING_PAYMENT, OrderStatus.CANCELLED);
    }

    // ---- PENDING ----

    @Test
    void pendingToProcessing_onDeliveryClaim() {
        String id = order(OrderStatus.PENDING, "COD", PaymentStatus.UNPAID);
        delivery.snapshot(id, true);
        assertState(id, OrderStatus.PROCESSING, PaymentStatus.UNPAID);
        assertOneStatusEvent(OrderStatus.PENDING, OrderStatus.PROCESSING);
    }

    @Test
    void pendingToCancelled_byBuyer_refundsPaidOrder() {
        String id = order(OrderStatus.PENDING, "VNPAY", PaymentStatus.PAID);
        buyer.cancelMyOrder(BUYER, id);
        assertState(id, OrderStatus.CANCELLED, PaymentStatus.REFUNDED);
        assertOneStatusEvent(OrderStatus.PENDING, OrderStatus.CANCELLED);
    }

    // ---- PROCESSING ----

    @Test
    void processingToShipped_onPickedUp() {
        String id = order(OrderStatus.PROCESSING, "COD", PaymentStatus.UNPAID);
        deliveryEvent(id, "PICKED_UP");
        assertState(id, OrderStatus.SHIPPED, PaymentStatus.UNPAID);
        assertOneStatusEvent(OrderStatus.PROCESSING, OrderStatus.SHIPPED);
    }

    @Test
    void processingToDelivered_onOutOfOrderDelivered_collectsCod() {
        String id = order(OrderStatus.PROCESSING, "COD", PaymentStatus.UNPAID);
        deliveryEvent(id, "DELIVERED");
        assertState(id, OrderStatus.DELIVERED, PaymentStatus.PAID);
    }

    @Test
    void processingToCancelled_byAdmin() {
        String id = order(OrderStatus.PROCESSING, "COD", PaymentStatus.UNPAID);
        admin.cancelOrder(ADMIN, id, "dispute");
        assertState(id, OrderStatus.CANCELLED, PaymentStatus.UNPAID);
        assertOneStatusEvent(OrderStatus.PROCESSING, OrderStatus.CANCELLED);
    }

    // ---- SHIPPED ----

    @Test
    void shippedToDelivered_collectsCod() {
        String id = order(OrderStatus.SHIPPED, "COD", PaymentStatus.UNPAID);
        deliveryEvent(id, "DELIVERED");
        assertState(id, OrderStatus.DELIVERED, PaymentStatus.PAID);
    }

    @Test
    void shippedToCancelled_onReturnedParcel_refundsPrepaidOrder() {
        String id = order(OrderStatus.SHIPPED, "VNPAY", PaymentStatus.PAID);
        deliveryEvent(id, "RETURNED");
        assertState(id, OrderStatus.CANCELLED, PaymentStatus.REFUNDED);
        assertOneStatusEvent(OrderStatus.SHIPPED, OrderStatus.CANCELLED);
    }

    // ---- DELIVERED ----

    @Test
    void deliveredToCompleted_byBuyer() {
        String id = order(OrderStatus.DELIVERED, "COD", PaymentStatus.PAID);
        buyer.completeMyOrder(BUYER, id);
        assertState(id, OrderStatus.COMPLETED, PaymentStatus.PAID);
        assertOneStatusEvent(OrderStatus.DELIVERED, OrderStatus.COMPLETED);
    }

    @Test
    void deliveredToCancelled_byAdmin_refundsCollectedCod() {
        String id = order(OrderStatus.DELIVERED, "COD", PaymentStatus.PAID);
        admin.cancelOrder(ADMIN, id, "dispute");
        assertState(id, OrderStatus.CANCELLED, PaymentStatus.REFUNDED);
    }

    // ---- Payment axis ----

    @Test
    void duplicatePaymentSucceededDoesNotPayTwice() {
        String id = order(OrderStatus.WAITING_PAYMENT, "VNPAY", PaymentStatus.UNPAID);
        payments.paid(id);
        payments.paid(id);
        assertState(id, OrderStatus.PENDING, PaymentStatus.PAID);
        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void paymentAfterCancellationIsRefundedExactlyOnce() {
        String id = order(OrderStatus.CANCELLED, "VNPAY", PaymentStatus.UNPAID);
        payments.paid(id);
        payments.paid(id);
        assertState(id, OrderStatus.CANCELLED, PaymentStatus.REFUNDED);
        assertThat(events.count()).as("chỉ một yêu cầu hoàn tiền").isEqualTo(1);
    }

    @Test
    void refundRequiresPriorPaymentAndHappensOnce() {
        var order = new Order();
        order.setStatus(OrderStatus.PENDING);
        assertThatThrownBy(order::markRefunded).isInstanceOf(IllegalOrderTransitionException.class);
        order.markPaid();
        assertThatThrownBy(order::markPaid).isInstanceOf(IllegalOrderTransitionException.class);
        order.markRefunded();
        assertThatThrownBy(order::markRefunded).isInstanceOf(IllegalOrderTransitionException.class);
    }

    // ---- Illegal transitions ----

    @Test
    void illegalTransitionOverHttpReturns409() throws Exception {
        String id = order(OrderStatus.COMPLETED, "COD", PaymentStatus.PAID);
        mvc.perform(
                        post("/api/orders/admin/{id}/cancel", id)
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        TestTokens.bearer(
                                                TestTokens.accessToken(
                                                        SECRET, ADMIN, List.of("ADMIN"))))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"too late\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ILLEGAL_ORDER_TRANSITION"));
        assertState(id, OrderStatus.COMPLETED, PaymentStatus.PAID);
        assertThat(events.count()).isZero();
    }

    @Test
    void deliveryEventThatDoesNotFitTheLifecycleIsIgnored() {
        String id = order(OrderStatus.COMPLETED, "COD", PaymentStatus.PAID);
        deliveryEvent(id, "PICKED_UP");
        assertState(id, OrderStatus.COMPLETED, PaymentStatus.PAID);
        assertThat(events.count()).isZero();
    }

    /** Toàn bộ cạnh KHÔNG có trong bảng đều bị chặn ở entity. */
    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void everyEdgeOutsideTheTableIsRejected(OrderStatus from) {
        Set<OrderStatus> allowed = EnumSet.noneOf(OrderStatus.class);
        for (OrderStatus to : OrderStatus.values()) {
            var order = new Order();
            order.setStatus(from);
            if (from.canTransitionTo(to)) {
                order.transitionTo(to);
                allowed.add(to);
            } else {
                assertThatThrownBy(() -> order.transitionTo(to))
                        .isInstanceOf(IllegalOrderTransitionException.class)
                        .hasFieldOrPropertyWithValue("code", "ILLEGAL_ORDER_TRANSITION");
                assertThat(order.getStatus()).isEqualTo(from);
            }
        }
        assertThat(allowed).doesNotContain(from);
        if (from == OrderStatus.COMPLETED || from == OrderStatus.CANCELLED) {
            assertThat(allowed).isEmpty();
        }
    }
}
