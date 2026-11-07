package com.vmarket.payment.event;

import org.springframework.stereotype.Component;

import com.vmarket.events.EventConsumer;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventType;
import com.vmarket.events.ReturnResolved;
import com.vmarket.payment.service.PaymentStore;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReturnResolvedConsumer implements EventConsumer<ReturnResolved> {

    private final PaymentStore store;

    @Override
    public String eventType() {
        return EventType.RETURN_RESOLVED;
    }

    @Override
    public Class<ReturnResolved> payloadType() {
        return ReturnResolved.class;
    }

    @Override
    public void handle(ReturnResolved payload, EventEnvelope envelope) {
        long amount = payload.refundAmount() != null ? payload.refundAmount().longValue() : 0L;
        store.processReturnRefund(
                payload.orderId(),
                payload.returnId(),
                amount,
                "Hoàn tiền yêu cầu trả hàng " + payload.returnId());
    }
}

