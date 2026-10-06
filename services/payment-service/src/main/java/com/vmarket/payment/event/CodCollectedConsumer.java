package com.vmarket.payment.event;

import com.vmarket.events.CodCollected;
import com.vmarket.events.EventConsumer;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventType;
import com.vmarket.payment.service.PaymentStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CodCollectedConsumer implements EventConsumer<CodCollected> {
    private final PaymentStore store;
    public String eventType() { return EventType.COD_COLLECTED; }
    public Class<CodCollected> payloadType() { return CodCollected.class; }
    public void handle(CodCollected payload, EventEnvelope envelope) { store.codCollected(payload); }
}
