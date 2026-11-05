package com.vmarket.notification.consumer;

import org.springframework.stereotype.Component;

import com.vmarket.events.EventConsumer;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventType;
import com.vmarket.events.ShopSuspended;
import com.vmarket.notification.service.NotificationDispatcher;
import com.vmarket.notification.service.NotificationTemplates;
import com.vmarket.notification.service.RecipientResolver;

/**
 * ShopSuspended → chủ gian hàng: bị đình chỉ kèm lý do (in-app + push + email).
 */
@Component
public class ShopSuspendedConsumer implements EventConsumer<ShopSuspended> {

	private final NotificationDispatcher dispatcher;
	private final NotificationTemplates templates;
	private final RecipientResolver recipients;


	public ShopSuspendedConsumer(NotificationDispatcher dispatcher, NotificationTemplates templates, RecipientResolver recipients) {
		this.dispatcher = dispatcher;
		this.templates = templates;
		this.recipients = recipients;
	}

	@Override
	public String eventType() {
		return EventType.SHOP_SUSPENDED;
	}

	@Override
	public Class<ShopSuspended> payloadType() {
		return ShopSuspended.class;
	}

	@Override
	public void handle(ShopSuspended event, EventEnvelope envelope) {
		recipients.rememberShopOwner(event.shopId(), event.ownerId(), event.shopName(), envelope);
		dispatcher.deliver(event.ownerId(), templates.shopSuspended(event.shopId(), event.shopName(), event.reason()),
				envelope);
	}
}
