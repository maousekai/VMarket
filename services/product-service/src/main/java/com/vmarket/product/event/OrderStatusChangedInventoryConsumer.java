package com.vmarket.product.event;

import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.dao.DuplicateKeyException;

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
			try {
				inventoryService.releaseOrDefer(payload.orderId());
			} catch (DuplicateKeyException ex) {
				if (!inventoryService.isReleasedOrPending(payload.orderId())) throw ex;
			}
		} else if ("COD".equals(normalize(payload.paymentMethod())) && COD_CONFIRMED_STATUSES.contains(status)) {
			try {
				inventoryService.confirmOrDefer(payload.orderId());
			} catch (DuplicateKeyException ex) {
				if (!inventoryService.isConfirmedOrPending(payload.orderId())) throw ex;
			}
		}
	}

	private String normalize(String value) {
		return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
	}
}
