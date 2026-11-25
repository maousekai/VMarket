package com.vmarket.notification.consumer;

import org.springframework.stereotype.Component;

import com.vmarket.events.EventConsumer;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventType;
import com.vmarket.events.ReturnRequested;
import com.vmarket.notification.service.NotificationDispatcher;
import com.vmarket.notification.service.NotificationTemplates;
import com.vmarket.notification.service.RecipientResolver;

import lombok.extern.slf4j.Slf4j;

/**
 * ReturnRequested → Seller của gian hàng: có yêu cầu trả hàng cần phản hồi trong 48 giờ (SRS §8.1,
 * FR-NOTI-01). Không có consumer nào thì Order Service không route được sự kiện này và outbox chặn mọi
 * sự kiện sau của đơn (kể cả ReturnResolved — hoàn tiền, nhập kho).
 */
@Slf4j
@Component
public class ReturnRequestedConsumer implements EventConsumer<ReturnRequested> {

	private final NotificationDispatcher dispatcher;
	private final NotificationTemplates templates;
	private final RecipientResolver recipients;

	public ReturnRequestedConsumer(NotificationDispatcher dispatcher, NotificationTemplates templates,
			RecipientResolver recipients) {
		this.dispatcher = dispatcher;
		this.templates = templates;
		this.recipients = recipients;
	}

	@Override
	public String eventType() {
		return EventType.RETURN_REQUESTED;
	}

	@Override
	public Class<ReturnRequested> payloadType() {
		return ReturnRequested.class;
	}

	@Override
	public void handle(ReturnRequested event, EventEnvelope envelope) {
		recipients.shopOwner(event.shopId()).ifPresentOrElse(
				ownerId -> dispatcher.deliver(ownerId,
						templates.returnRequested(event.orderId(), event.returnId(), event.shopId()), envelope),
				() -> log.warn("Không xác định được chủ gian hàng {} cho yêu cầu trả hàng {}", event.shopId(),
						event.returnId()));
	}
}
