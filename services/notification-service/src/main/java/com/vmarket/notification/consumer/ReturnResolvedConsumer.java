package com.vmarket.notification.consumer;

import org.springframework.stereotype.Component;

import com.vmarket.events.EventConsumer;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventType;
import com.vmarket.events.ReturnResolved;
import com.vmarket.notification.service.NotificationDispatcher;
import com.vmarket.notification.service.NotificationTemplates;
import com.vmarket.notification.service.RecipientResolver;

/**
 * ReturnResolved → Buyer: kết quả yêu cầu trả hàng (in-app + push + email).
 */
@Component
public class ReturnResolvedConsumer implements EventConsumer<ReturnResolved> {

	private final NotificationDispatcher dispatcher;
	private final NotificationTemplates templates;
	private final RecipientResolver recipients;


	public ReturnResolvedConsumer(NotificationDispatcher dispatcher, NotificationTemplates templates, RecipientResolver recipients) {
		this.dispatcher = dispatcher;
		this.templates = templates;
		this.recipients = recipients;
	}

	@Override
	public String eventType() {
		return EventType.RETURN_RESOLVED;
	}

	@Override
	public Class<ReturnResolved> payloadType() {
		return ReturnResolved.class;
	}

	@Override
	public void handle(ReturnResolved event, EventEnvelope envelope) {
		recipients.order(event.orderId()).ifPresent(order -> dispatcher.deliver(order.userId(),
				templates.returnResolved(order.id(), event.returnId(), event.restock()), envelope));
	}
}
