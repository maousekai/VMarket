package com.vmarket.order.service;

import com.vmarket.events.*;
import com.vmarket.order.config.OrderPaymentProperties;
import com.vmarket.order.entity.Order;
import com.vmarket.order.entity.OrderStatus;
import com.vmarket.order.entity.PaymentStatus;
import com.vmarket.order.event.Outbox;
import com.vmarket.order.repository.OrderRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderPaymentLifecycle {
    private final OrderRepository repository;
    private final Outbox outbox;
    private final OrderPaymentProperties timing;

    @Transactional
    public void paid(String id) {
        var optional = repository.lock(id);
        if (optional.isEmpty()) return;
        var order = optional.get();
        // PaymentSucceeded giao lại (at-least-once) khi đơn đã PAID/REFUND_PENDING: không thu lần hai,
        // không phát thêm yêu cầu hoàn tiền (PBL6-63).
        if (order.getPaymentStatus() != PaymentStatus.UNPAID) return;
        if (order.getStatus() == OrderStatus.WAITING_PAYMENT
                && "VNPAY".equals(order.getPaymentMethod())) {
            order.markPaid();
            change(order, OrderStatus.PENDING);
        } else if (order.getStatus() == OrderStatus.CANCELLED) {
            // Reconcile success racing cancellation, including cancellation consumed before the
            // webhook: tiền đã vào sau khi huỷ nên phải hoàn đúng một lần.
            order.markPaid();
            order.markRefundPending();
            outbox.add(
                    EventType.ORDER_STATUS_CHANGED,
                    new OrderStatusChanged(id, "CANCELLED", "CANCELLED", order.getPaymentMethod()));
        }
    }

    @Transactional
    public void fail(String id) {
        var optional = repository.lock(id);
        if (optional.isPresent() && optional.get().getStatus() == OrderStatus.WAITING_PAYMENT)
            change(optional.get(), OrderStatus.CANCELLED);
    }

    @Transactional
    public void expire(String id, java.time.Instant now) {
        var optional = repository.lock(id);
        if (optional.isPresent()
                && optional.get().getStatus() == OrderStatus.WAITING_PAYMENT
                && optional.get().getPaymentExpiresAt() != null
                && !now.isBefore(
                        optional.get().getPaymentExpiresAt().plus(timing.getFallbackDelay())))
            change(optional.get(), OrderStatus.CANCELLED);
    }

    @Transactional
    public void reserved(String id) {
        repository.lock(id).ifPresent(o -> o.setStockReserved(true));
    }

    @Transactional
    public void stockFailed(String id) {
        repository
                .lock(id)
                .ifPresent(
                        o -> {
                            if (o.getStatus() == OrderStatus.PENDING
                                    || o.getStatus() == OrderStatus.WAITING_PAYMENT)
                                change(o, OrderStatus.CANCELLED);
                        });
    }

    private void change(Order order, OrderStatus status) {
        var previous = order.transitionTo(status);
        outbox.add(
                EventType.ORDER_STATUS_CHANGED,
                new OrderStatusChanged(
                        order.getId(), previous.name(), status.name(), order.getPaymentMethod()));
    }
}
