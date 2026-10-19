package com.vmarket.order.event;

import com.vmarket.events.*;
import com.vmarket.order.service.OrderPaymentLifecycle;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentSucceededConsumer implements EventConsumer<PaymentSucceeded> {
    private final OrderPaymentLifecycle lifecycle;

    public String eventType() {
        return EventType.PAYMENT_SUCCEEDED;
    }

    public Class<PaymentSucceeded> payloadType() {
        return PaymentSucceeded.class;
    }

    public void handle(PaymentSucceeded payload, EventEnvelope envelope) {
        lifecycle.paid(payload.orderId());
    }
}
