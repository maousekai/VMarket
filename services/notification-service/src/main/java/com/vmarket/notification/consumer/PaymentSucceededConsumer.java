package com.vmarket.notification.consumer;

import org.springframework.stereotype.Component;

import com.vmarket.events.EventConsumer;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventType;
import com.vmarket.events.PaymentSucceeded;
import com.vmarket.notification.service.NotificationDispatcher;
import com.vmarket.notification.service.NotificationTemplates;
import com.vmarket.notification.service.RecipientResolver;

/**
 * PaymentSucceeded → Buyer: thanh toán thành công (in-app + push + email); Seller: đơn mới
 * đã thanh toán, cần xử lý (UC đặt hàng bước 7).
 */
@Component
public class PaymentSucceededConsumer implements EventConsumer<PaymentSucceeded> {

	private final NotificationDispatcher dispatcher;
	private final NotificationTemplates templates;
	private final RecipientResolver recipients;


	public PaymentSucceededConsumer(NotificationDispatcher dispatcher, NotificationTemplates templates, RecipientResolver recipients) {
		this.dispatcher = dispatcher;
		this.templates = templates;
		this.recipients = recipients;
	}

	@Override
	public String eventType() {
		return EventType.PAYMENT_SUCCEEDED;
	}

	@Override
	public Class<PaymentSucceeded> payloadType() {
		return PaymentSucceeded.class;
	}

	@Override
	public void handle(PaymentSucceeded event, EventEnvelope envelope) {
		recipients.order(event.orderId()).ifPresent(order -> {
			dispatcher.deliver(order.userId(), templates.paymentSucceeded(order), envelope);
			recipients.sellers(order).forEach(
					(shopId, ownerId) -> dispatcher.deliver(ownerId, templates.newOrder(order, shopId), envelope));
		});
	}
}
