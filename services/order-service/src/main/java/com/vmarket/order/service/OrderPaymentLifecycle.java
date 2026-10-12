package com.vmarket.order.service;
import com.vmarket.order.repository.OrderRepository;
import com.vmarket.order.entity.Order;
import com.vmarket.order.entity.OrderStatus;
import com.vmarket.order.event.Outbox;
import com.vmarket.events.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
@Service @RequiredArgsConstructor
public class OrderPaymentLifecycle {
    private final OrderRepository repository; private final Outbox outbox;
    @Transactional public void paid(String id) {
        var optional = repository.lock(id); if (optional.isEmpty()) return;
        var order = optional.get();
        if (order.getStatus() == OrderStatus.WAITING_PAYMENT && "PAYOS".equals(order.getPaymentMethod())) {
            change(order, OrderStatus.PENDING);
        } else if (order.getStatus() == OrderStatus.CANCELLED) {
            // Reconcile success racing cancellation, including cancellation consumed before the webhook.
            outbox.add(EventType.ORDER_STATUS_CHANGED, new OrderStatusChanged(id, "CANCELLED", "CANCELLED", order.getPaymentMethod()));
        }
    }
    @Transactional public void fail(String id) { var optional = repository.lock(id); if (optional.isPresent() && optional.get().getStatus() == OrderStatus.WAITING_PAYMENT) change(optional.get(), OrderStatus.CANCELLED); }
    @Transactional public void expire(String id, java.time.Instant now) {
        var optional = repository.lock(id);
        if (optional.isPresent() && optional.get().getStatus() == OrderStatus.WAITING_PAYMENT && optional.get().getPaymentExpiresAt() != null && !now.isBefore(optional.get().getPaymentExpiresAt())) change(optional.get(), OrderStatus.CANCELLED);
    }
    @Transactional public void reserved(String id) { repository.lock(id).ifPresent(o -> o.setStockReserved(true)); }
    @Transactional public void stockFailed(String id) { repository.lock(id).ifPresent(o -> { if (o.getStatus() == OrderStatus.PENDING || o.getStatus() == OrderStatus.WAITING_PAYMENT) change(o, OrderStatus.CANCELLED); }); }
    private void change(Order order, OrderStatus status) { var previous = order.getStatus(); order.setStatus(status); outbox.add(EventType.ORDER_STATUS_CHANGED, new OrderStatusChanged(order.getId(), previous.name(), status.name(), order.getPaymentMethod())); }
}
