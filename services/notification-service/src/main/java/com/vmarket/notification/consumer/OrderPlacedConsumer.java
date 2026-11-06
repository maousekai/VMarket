package com.vmarket.notification.consumer;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.vmarket.events.EventConsumer;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventType;
import com.vmarket.events.OrderPlaced;
import com.vmarket.notification.service.NotificationDispatcher;
import com.vmarket.notification.service.NotificationDispatcher.Delivery;
import com.vmarket.notification.service.NotificationTemplates;
import com.vmarket.notification.service.RecipientResolver;

/**
 * OrderPlaced → Buyer: xác nhận đặt hàng (in-app + push + email). Seller: báo đơn mới ngay với
 * đơn COD; đơn thanh toán online chỉ báo Seller khi PaymentSucceeded (tránh báo đơn chưa trả tiền).
 */
@Component
public class OrderPlacedConsumer implements EventConsumer<OrderPlaced> {

	private final NotificationDispatcher dispatcher;
	private final NotificationTemplates templates;
	private final RecipientResolver recipients;


	public OrderPlacedConsumer(NotificationDispatcher dispatcher, NotificationTemplates templates, RecipientResolver recipients) {
		this.dispatcher = dispatcher;
		this.templates = templates;
		this.recipients = recipients;
	}

	@Override
	public String eventType() {
		return EventType.ORDER_PLACED;
	}

	@Override
	public Class<OrderPlaced> payloadType() {
		return OrderPlaced.class;
	}

	@Override
	public void handle(OrderPlaced event, EventEnvelope envelope) {
		recipients.order(event.orderId()).ifPresent(order -> {
			List<Delivery> deliveries = new ArrayList<>();
			deliveries.add(new Delivery(order.userId(), templates.orderPlaced(order)));
			if (order.isCod()) {
				recipients.sellers(order).forEach(
						(shopId, ownerId) -> deliveries.add(new Delivery(ownerId, templates.newOrder(order, shopId))));
			}
			dispatcher.deliverAll(deliveries, envelope);
		});
	}
}
