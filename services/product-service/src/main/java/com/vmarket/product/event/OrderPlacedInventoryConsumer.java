package com.vmarket.product.event;

import org.springframework.stereotype.Component;

import com.vmarket.events.EventConsumer;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventType;
import com.vmarket.events.OrderPlaced;
import com.vmarket.events.StockReservationFailed;
import com.vmarket.product.dto.InventoryRequest;
import com.vmarket.product.exception.ApiException;
import com.vmarket.product.service.InventoryService;

@Component
public class OrderPlacedInventoryConsumer implements EventConsumer<OrderPlaced> {
	private final InventoryService inventoryService;
	private final ProductEventPublisher publisher;

	public OrderPlacedInventoryConsumer(InventoryService inventoryService, ProductEventPublisher publisher) {
		this.inventoryService = inventoryService;
		this.publisher = publisher;
	}

	@Override public String eventType() { return EventType.ORDER_PLACED; }
	@Override public Class<OrderPlaced> payloadType() { return OrderPlaced.class; }

	@Override
	public void handle(OrderPlaced payload, EventEnvelope envelope) {
		try {
			inventoryService.reserve(new InventoryRequest(payload.orderId(), payload.items() == null ? null : payload.items().stream()
					.map(item -> new InventoryRequest.InventoryItem(
							item.productId(), item.variantId(), item.quantity())).toList()));
		} catch (ApiException ex) {
			publisher.publishStockReservationFailed(
					new StockReservationFailed(payload.orderId(), ex.getCode(), ex.getMessage()));
		}
	}
}
