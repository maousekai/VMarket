package com.vmarket.payment.event;
import com.vmarket.events.*;
import com.vmarket.payment.service.PaymentStore;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
@Component @RequiredArgsConstructor
public class OrderCancellationConsumer implements EventConsumer<OrderStatusChanged> {
    private final PaymentStore store;
    public String eventType() { return EventType.ORDER_STATUS_CHANGED; }
    public Class<OrderStatusChanged> payloadType() { return OrderStatusChanged.class; }
    public void handle(OrderStatusChanged payload, EventEnvelope envelope) { if ("CANCELLED".equals(payload.status())) store.cancelled(payload.orderId()); }
}
