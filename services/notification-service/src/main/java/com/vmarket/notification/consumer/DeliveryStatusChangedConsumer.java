package com.vmarket.notification.consumer;

import org.springframework.stereotype.Component;

import com.vmarket.events.EventConsumer;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventType;
import com.vmarket.events.DeliveryStatusChanged;
import com.vmarket.notification.service.NotificationDispatcher;
import com.vmarket.notification.service.NotificationTemplates;
import com.vmarket.notification.service.RecipientResolver;

/**
 * DeliveryStatusChanged → Buyer: báo khi một lần giao thất bại (FAILED). Các trạng thái khác
 * đã được order-service chuyển thành OrderStatusChanged nên không báo lặp ở đây.
 */
@Component
public class DeliveryStatusChangedConsumer implements EventConsumer<DeliveryStatusChanged> {

	private final NotificationDispatcher dispatcher;
	private final NotificationTemplates templates;
	private final RecipientResolver recipients;


	public DeliveryStatusChangedConsumer(NotificationDispatcher dispatcher, NotificationTemplates templates, RecipientResolver recipients) {
		this.dispatcher = dispatcher;
		this.templates = templates;
		this.recipients = recipients;
	}

	@Override
	public String eventType() {
		return EventType.DELIVERY_STATUS_CHANGED;
	}

	@Override
	public Class<DeliveryStatusChanged> payloadType() {
		return DeliveryStatusChanged.class;
	}

	@Override
	public void handle(DeliveryStatusChanged event, EventEnvelope envelope) {
		if ("FAILED".equals(event.status())) {
			recipients.order(event.orderId()).ifPresent(order -> dispatcher.deliver(order.userId(),
					templates.deliveryFailed(order.id(), event.publicReason(), event.earliestRetryAt()), envelope));
			return;
		}
		// Shipper da tra hang hoan: seller tung shop xac nhan "Da nhan lai hang".
		if ("RETURN_DROPPED_OFF".equals(event.status())) {
			recipients.order(event.orderId()).ifPresent(order -> {
				var sellers = recipients.sellers(order);
				java.util.List<NotificationDispatcher.Delivery> deliveries = new java.util.ArrayList<>();
				sellers.owners().forEach((shopId, ownerId) -> deliveries.add(
						new NotificationDispatcher.Delivery(ownerId, templates.sellerReturnDroppedOff(order.id(), shopId))));
				dispatcher.deliverAll(deliveries, envelope, sellers.failure());
			});
		}
	}
}
