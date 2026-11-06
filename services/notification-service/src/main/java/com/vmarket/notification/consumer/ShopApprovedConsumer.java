package com.vmarket.notification.consumer;

import org.springframework.stereotype.Component;

import com.vmarket.events.EventConsumer;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventType;
import com.vmarket.events.ShopApproved;
import com.vmarket.notification.service.NotificationDispatcher;
import com.vmarket.notification.service.NotificationTemplates;
import com.vmarket.notification.service.RecipientResolver;

/**
 * ShopApproved → chủ gian hàng: kết quả duyệt / gỡ đình chỉ (in-app + push + email, UC duyệt shop
 * bước 4). Đồng thời ghi bản chiếu shopId → ownerId để báo Seller khi có đơn.
 */
@Component
public class ShopApprovedConsumer implements EventConsumer<ShopApproved> {

	private final NotificationDispatcher dispatcher;
	private final NotificationTemplates templates;
	private final RecipientResolver recipients;


	public ShopApprovedConsumer(NotificationDispatcher dispatcher, NotificationTemplates templates, RecipientResolver recipients) {
		this.dispatcher = dispatcher;
		this.templates = templates;
		this.recipients = recipients;
	}

	@Override
	public String eventType() {
		return EventType.SHOP_APPROVED;
	}

	@Override
	public Class<ShopApproved> payloadType() {
		return ShopApproved.class;
	}

	@Override
	public void handle(ShopApproved event, EventEnvelope envelope) {
		recipients.rememberShopOwner(event.shopId(), event.ownerId(), event.shopName(), envelope);
		dispatcher.deliver(event.ownerId(), templates.shopApproved(event.shopId(), event.shopName(), event.reinstated()),
				envelope);
	}
}
