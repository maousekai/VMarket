package com.vmarket.notification.consumer;

import java.util.List;

import org.springframework.stereotype.Component;

import com.vmarket.events.DeliveryAssignmentFailed;
import com.vmarket.events.EventConsumer;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventType;
import com.vmarket.notification.client.InternalClients;
import com.vmarket.notification.service.NotificationDispatcher;
import com.vmarket.notification.service.NotificationTemplates;

/**
 * DeliveryAssignmentFailed -> moi admin dang hoat dong: chuyen giao chua co shipper (phuong la hoac
 * khong con shipper phu hop), can phan cong tay (review PR 68 lan 2, muc 4.8).
 */
@Component
public class DeliveryAssignmentFailedConsumer implements EventConsumer<DeliveryAssignmentFailed> {

	private final NotificationDispatcher dispatcher;
	private final NotificationTemplates templates;
	private final InternalClients clients;

	public DeliveryAssignmentFailedConsumer(NotificationDispatcher dispatcher, NotificationTemplates templates,
			InternalClients clients) {
		this.dispatcher = dispatcher;
		this.templates = templates;
		this.clients = clients;
	}

	@Override
	public String eventType() {
		return EventType.DELIVERY_ASSIGNMENT_FAILED;
	}

	@Override
	public Class<DeliveryAssignmentFailed> payloadType() {
		return DeliveryAssignmentFailed.class;
	}

	@Override
	public void handle(DeliveryAssignmentFailed event, EventEnvelope envelope) {
		var message = templates.deliveryNeedsDispatch(event.orderId(), event.reason());
		List<NotificationDispatcher.Delivery> deliveries = clients.admins().stream()
				.map(admin -> new NotificationDispatcher.Delivery(admin, message))
				.toList();
		dispatcher.deliverAll(deliveries, envelope);
	}
}
