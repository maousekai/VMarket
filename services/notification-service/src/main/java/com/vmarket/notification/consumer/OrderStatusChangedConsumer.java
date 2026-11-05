package com.vmarket.notification.consumer;

import org.springframework.stereotype.Component;

import com.vmarket.events.EventConsumer;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventType;
import com.vmarket.events.OrderStatusChanged;
import com.vmarket.notification.service.NotificationDispatcher;
import com.vmarket.notification.service.NotificationTemplates;
import com.vmarket.notification.service.RecipientResolver;

/**
 * OrderStatusChanged → Buyer: cập nhật trạng thái (in-app + push + email) cho PROCESSING /
 * SHIPPED / DELIVERED / CANCELLED. Seller: đơn bị huỷ hoặc đã giao.
 */
@Component
public class OrderStatusChangedConsumer implements EventConsumer<OrderStatusChanged> {

	private final NotificationDispatcher dispatcher;
	private final NotificationTemplates templates;
	private final RecipientResolver recipients;


	public OrderStatusChangedConsumer(NotificationDispatcher dispatcher, NotificationTemplates templates, RecipientResolver recipients) {
		this.dispatcher = dispatcher;
		this.templates = templates;
		this.recipients = recipients;
	}

	@Override
	public String eventType() {
		return EventType.ORDER_STATUS_CHANGED;
	}

	@Override
	public Class<OrderStatusChanged> payloadType() {
		return OrderStatusChanged.class;
	}

	@Override
	public void handle(OrderStatusChanged event, EventEnvelope envelope) {
		if (event.status() == null || event.status().equals(event.previousStatus())) {
			return;
		}
		var buyerMessage = templates.orderStatusChanged(event.orderId(), event.status());
		boolean sellerRelevant = "CANCELLED".equals(event.status()) || "DELIVERED".equals(event.status());
		if (buyerMessage == null && !sellerRelevant) {
			return;
		}
		recipients.order(event.orderId()).ifPresent(order -> {
			if (buyerMessage != null) {
				dispatcher.deliver(order.userId(), buyerMessage, envelope);
			}
			if (sellerRelevant) {
				recipients.sellers(order).forEach((shopId, ownerId) -> dispatcher.deliver(ownerId,
						"CANCELLED".equals(event.status())
								? templates.sellerOrderCancelled(order.id(), shopId)
								: templates.sellerOrderDelivered(order.id(), shopId),
						envelope));
			}
		});
	}
}
