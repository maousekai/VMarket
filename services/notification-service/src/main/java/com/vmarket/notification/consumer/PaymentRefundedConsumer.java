package com.vmarket.notification.consumer;

import org.springframework.stereotype.Component;

import com.vmarket.events.EventConsumer;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventType;
import com.vmarket.events.PaymentRefunded;
import com.vmarket.notification.service.NotificationDispatcher;
import com.vmarket.notification.service.NotificationTemplates;
import com.vmarket.notification.service.RecipientResolver;

/** PaymentRefunded -> Buyer: "Da hoan tien" (review PR 68 lan 2, muc 4.8). */
@Component
public class PaymentRefundedConsumer implements EventConsumer<PaymentRefunded> {

	private final NotificationDispatcher dispatcher;
	private final NotificationTemplates templates;
	private final RecipientResolver recipients;

	public PaymentRefundedConsumer(NotificationDispatcher dispatcher, NotificationTemplates templates,
			RecipientResolver recipients) {
		this.dispatcher = dispatcher;
		this.templates = templates;
		this.recipients = recipients;
	}

	@Override
	public String eventType() {
		return EventType.PAYMENT_REFUNDED;
	}

	@Override
	public Class<PaymentRefunded> payloadType() {
		return PaymentRefunded.class;
	}

	@Override
	public void handle(PaymentRefunded event, EventEnvelope envelope) {
		recipients.order(event.orderId()).ifPresent(order -> dispatcher.deliver(order.userId(),
				templates.paymentRefunded(order.id(), event.amount()), envelope));
	}
}
