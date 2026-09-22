package com.vmarket.product.event;

import org.springframework.stereotype.Component;
import org.springframework.dao.DuplicateKeyException;

import com.vmarket.events.EventConsumer;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventType;
import com.vmarket.events.PaymentSucceeded;
import com.vmarket.product.service.InventoryService;

@Component
public class PaymentSucceededInventoryConsumer implements EventConsumer<PaymentSucceeded> {
	private final InventoryService inventoryService;
	public PaymentSucceededInventoryConsumer(InventoryService inventoryService) { this.inventoryService = inventoryService; }
	@Override public String eventType() { return EventType.PAYMENT_SUCCEEDED; }
	@Override public Class<PaymentSucceeded> payloadType() { return PaymentSucceeded.class; }
	@Override public void handle(PaymentSucceeded payload, EventEnvelope envelope) {
		try {
			inventoryService.confirmOrDefer(payload.orderId());
		} catch (DuplicateKeyException ex) {
			if (!inventoryService.isConfirmedOrPending(payload.orderId())) throw ex;
		}
	}
}
