package com.vmarket.order.delivery;
import com.vmarket.events.*;
import com.vmarket.events.DeliveryStatusChanged;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class DeliveryEventConsumer implements EventConsumer<DeliveryStatusChanged> {
    private final OrderDeliveryBridge bridge;
    @Override public String eventType() { return EventType.DELIVERY_STATUS_CHANGED; }
    @Override public Class<DeliveryStatusChanged> payloadType() { return DeliveryStatusChanged.class; }
    @Override public void handle(DeliveryStatusChanged payload, EventEnvelope event) {
        bridge.apply(payload);
    }
}
