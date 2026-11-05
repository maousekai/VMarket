package com.vmarket.notification.consumer;

import org.springframework.stereotype.Component;

import com.vmarket.events.EventConsumer;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventType;
import com.vmarket.events.PaymentFailed;
import com.vmarket.notification.service.NotificationDispatcher;
import com.vmarket.notification.service.NotificationTemplates;
import com.vmarket.notification.service.RecipientResolver;

/**
 * PaymentFailed → Buyer: thanh toán không thành công (in-app + push).
 */
@Component
public class PaymentFailedConsumer implements EventConsumer<PaymentFailed> {

	private final NotificationDispatcher dispatcher;
	private final NotificationTemplates templates;
	private final RecipientResolver recipients;


	public PaymentFailedConsumer(NotificationDispatcher dispatcher, NotificationTemplates templates, RecipientResolver recipients) {
		this.dispatcher = dispatcher;
		this.templates = templates;
		this.recipients = recipients;
	}

	@Override
	public String eventType() {
		return EventType.PAYMENT_FAILED;
	}

	@Override
	public Class<PaymentFailed> payloadType() {
		return PaymentFailed.class;
	}

	@Override
	public void handle(PaymentFailed event, EventEnvelope envelope) {
		recipients.order(event.orderId()).ifPresent(order -> dispatcher.deliver(order.userId(),
				templates.paymentFailed(order.id(), event.reason()), envelope));
	}
}
