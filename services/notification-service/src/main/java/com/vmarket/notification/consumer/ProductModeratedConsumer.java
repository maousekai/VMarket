package com.vmarket.notification.consumer;

import org.springframework.stereotype.Component;

import com.vmarket.events.EventConsumer;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventType;
import com.vmarket.events.ProductModerated;
import com.vmarket.notification.service.NotificationDispatcher;
import com.vmarket.notification.service.NotificationTemplates;

/**
 * ProductModerated → Seller: sản phẩm bị gỡ / được khôi phục (in-app + push).
 */
@Component
public class ProductModeratedConsumer implements EventConsumer<ProductModerated> {

	private final NotificationDispatcher dispatcher;
	private final NotificationTemplates templates;

	public ProductModeratedConsumer(NotificationDispatcher dispatcher, NotificationTemplates templates) {
		this.dispatcher = dispatcher;
		this.templates = templates;
	}

	@Override
	public String eventType() {
		return EventType.PRODUCT_MODERATED;
	}

	@Override
	public Class<ProductModerated> payloadType() {
		return ProductModerated.class;
	}

	@Override
	public void handle(ProductModerated event, EventEnvelope envelope) {
		dispatcher.deliver(event.sellerId(),
				templates.productModerated(event.productId(), event.shopId(), event.removed(), event.reason()), envelope);
	}
}
