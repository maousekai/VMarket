package com.vmarket.product.event;

import org.springframework.stereotype.Component;

import com.vmarket.events.EventConsumer;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventType;
import com.vmarket.events.OrderCancelled;
import com.vmarket.product.service.InventoryService;

@Component
public class OrderCancelledInventoryConsumer implements EventConsumer<OrderCancelled> {
	private final InventoryService inventoryService;
	public OrderCancelledInventoryConsumer(InventoryService inventoryService) { this.inventoryService = inventoryService; }
	@Override public String eventType() { return EventType.ORDER_CANCELLED; }
	@Override public Class<OrderCancelled> payloadType() { return OrderCancelled.class; }
	@Override public void handle(OrderCancelled payload, EventEnvelope envelope) { inventoryService.release(payload.orderId()); }
}
