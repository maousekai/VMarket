package com.vmarket.order.event;

import com.vmarket.events.*;
import com.vmarket.order.service.OrderPaymentLifecycle;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentFailedConsumer implements EventConsumer<PaymentFailed> {
    private final OrderPaymentLifecycle lifecycle;

    public String eventType() {
        return EventType.PAYMENT_FAILED;
    }

    public Class<PaymentFailed> payloadType() {
        return PaymentFailed.class;
    }

    public void handle(PaymentFailed payload, EventEnvelope envelope) {
        if ("EXPIRED".equals(payload.reason()) || "CANCELLED".equals(payload.reason()))
            lifecycle.fail(payload.orderId());
    }
}
