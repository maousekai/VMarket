package com.vmarket.product.event;

import org.springframework.stereotype.Component;

import com.vmarket.events.EventConsumer;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventType;
import com.vmarket.events.OrderConfirmed;
import com.vmarket.product.service.InventoryService;

@Component
public class OrderConfirmedInventoryConsumer implements EventConsumer<OrderConfirmed> {
	private final InventoryService inventoryService;
	public OrderConfirmedInventoryConsumer(InventoryService inventoryService) { this.inventoryService = inventoryService; }
	@Override public String eventType() { return EventType.ORDER_CONFIRMED; }
	@Override public Class<OrderConfirmed> payloadType() { return OrderConfirmed.class; }
	@Override public void handle(OrderConfirmed payload, EventEnvelope envelope) { inventoryService.confirm(payload.orderId()); }
}
