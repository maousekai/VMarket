package com.vmarket.order.event;

import com.vmarket.events.*;
import com.vmarket.order.service.OrderPaymentLifecycle;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StockReservedConsumer implements EventConsumer<StockReserved> {
    private final OrderPaymentLifecycle lifecycle;

    public String eventType() {
        return EventType.STOCK_RESERVED;
    }

    public Class<StockReserved> payloadType() {
        return StockReserved.class;
    }

    public void handle(StockReserved payload, EventEnvelope envelope) {
        lifecycle.reserved(payload.orderId());
    }
}
