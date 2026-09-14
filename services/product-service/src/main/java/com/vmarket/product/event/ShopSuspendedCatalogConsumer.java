package com.vmarket.product.event;

import org.springframework.stereotype.Component;

import com.vmarket.events.EventConsumer;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventType;
import com.vmarket.events.ShopSuspended;
import com.vmarket.product.service.ShopAccessService;

@Component
public class ShopSuspendedCatalogConsumer implements EventConsumer<ShopSuspended> {
	private final ShopAccessService shopAccessService;
	public ShopSuspendedCatalogConsumer(ShopAccessService shopAccessService) { this.shopAccessService = shopAccessService; }
	@Override public String eventType() { return EventType.SHOP_SUSPENDED; }
	@Override public Class<ShopSuspended> payloadType() { return ShopSuspended.class; }
	@Override public void handle(ShopSuspended payload, EventEnvelope envelope) { shopAccessService.suspend(payload.shopId()); }
}
