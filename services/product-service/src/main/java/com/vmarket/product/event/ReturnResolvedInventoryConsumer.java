package com.vmarket.product.event;

import org.springframework.stereotype.Component;

import com.vmarket.events.EventConsumer;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventType;
import com.vmarket.events.ReturnResolved;
import com.vmarket.product.service.InventoryService;

@Component
public class ReturnResolvedInventoryConsumer implements EventConsumer<ReturnResolved> {
	private final InventoryService inventoryService;

	public ReturnResolvedInventoryConsumer(InventoryService inventoryService) {
		this.inventoryService = inventoryService;
	}

	@Override public String eventType() { return EventType.RETURN_RESOLVED; }
	@Override public Class<ReturnResolved> payloadType() { return ReturnResolved.class; }

	@Override
	public void handle(ReturnResolved payload, EventEnvelope envelope) {
		if (payload.restock()) {
			inventoryService.restockReturn(payload.returnId(), payload.orderId(), payload.items());
		}
	}
}
