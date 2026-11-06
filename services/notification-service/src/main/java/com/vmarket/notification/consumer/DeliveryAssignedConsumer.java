package com.vmarket.notification.consumer;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.vmarket.events.EventConsumer;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventType;
import com.vmarket.events.DeliveryAssigned;
import com.vmarket.notification.service.NotificationDispatcher;
import com.vmarket.notification.service.NotificationDispatcher.Delivery;
import com.vmarket.notification.service.NotificationTemplates;
import com.vmarket.notification.service.RecipientResolver;
import com.vmarket.notification.service.RecipientResolver.SellerLookup;

/**
 * DeliveryAssigned → Shipper: chuyến mới (in-app + push); Seller: đơn đã có shipper, chuẩn bị
 * bàn giao. Nhận từ queue riêng {@code notification-service.delivery-assigned} do delivery-service
 * khai báo sẵn (xem {@code DeliveryAssignedListenerConfig}).
 */
@Component
public class DeliveryAssignedConsumer implements EventConsumer<DeliveryAssigned> {

	private final NotificationDispatcher dispatcher;
	private final NotificationTemplates templates;
	private final RecipientResolver recipients;


	public DeliveryAssignedConsumer(NotificationDispatcher dispatcher, NotificationTemplates templates, RecipientResolver recipients) {
		this.dispatcher = dispatcher;
		this.templates = templates;
		this.recipients = recipients;
	}

	@Override
	public String eventType() {
		return EventType.DELIVERY_ASSIGNED;
	}

	@Override
	public Class<DeliveryAssigned> payloadType() {
		return DeliveryAssigned.class;
	}

	@Override
	public void handle(DeliveryAssigned event, EventEnvelope envelope) {
		List<Delivery> deliveries = new ArrayList<>();
		deliveries.add(new Delivery(event.shipperId(), templates.shipperAssigned(event.orderId(), event.assignmentId())));
		RuntimeException lookupFailure = null;
		var order = recipients.order(event.orderId());
		if (order.isPresent()) {
			SellerLookup sellers = recipients.sellers(order.get());
			sellers.owners().forEach((shopId, ownerId) -> deliveries
					.add(new Delivery(ownerId, templates.sellerDeliveryAssigned(order.get().id(), shopId))));
			lookupFailure = sellers.failure();
		}
		dispatcher.deliverAll(deliveries, envelope, lookupFailure);
	}
}
