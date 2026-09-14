package com.vmarket.product.event;

import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.vmarket.events.EventConsumer;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventType;
import com.vmarket.events.OrderStatusChanged;
import com.vmarket.product.service.InventoryService;

/** Ánh xạ vòng đời đơn trong SRS sang thao tác chốt/hoàn tồn kho. */
@Component
public class OrderStatusChangedInventoryConsumer implements EventConsumer<OrderStatusChanged> {
	private static final Set<String> COD_CONFIRMED_STATUSES = Set.of("CONFIRMED", "PREPARING");
	private final InventoryService inventoryService;

	public OrderStatusChangedInventoryConsumer(InventoryService inventoryService) {
		this.inventoryService = inventoryService;
	}

	@Override public String eventType() { return EventType.ORDER_STATUS_CHANGED; }
	@Override public Class<OrderStatusChanged> payloadType() { return OrderStatusChanged.class; }

	@Override
	public void handle(OrderStatusChanged payload, EventEnvelope envelope) {
		String status = normalize(payload.status());
		if ("CANCELLED".equals(status)) {
			inventoryService.release(payload.orderId());
		} else if ("COD".equals(normalize(payload.paymentMethod())) && COD_CONFIRMED_STATUSES.contains(status)) {
			inventoryService.confirm(payload.orderId());
		}
	}

	private String normalize(String value) {
		return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
	}
}
