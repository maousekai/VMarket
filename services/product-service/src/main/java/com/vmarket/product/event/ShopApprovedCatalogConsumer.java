package com.vmarket.product.event;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.vmarket.events.EventConsumer;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventType;
import com.vmarket.events.ShopApproved;
import com.vmarket.product.service.ShopAccessService;

@Component
public class ShopApprovedCatalogConsumer implements EventConsumer<ShopApproved> {
	private final ShopAccessService shopAccessService;
	public ShopApprovedCatalogConsumer(ShopAccessService shopAccessService) { this.shopAccessService = shopAccessService; }
	@Override public String eventType() { return EventType.SHOP_APPROVED; }
	@Override public Class<ShopApproved> payloadType() { return ShopApproved.class; }
	@Override public void handle(ShopApproved payload, EventEnvelope envelope) {
		shopAccessService.approve(payload.shopId(), payload.sellerId(),
				Instant.ofEpochMilli(envelope.timestamp()), false);
	}
}
