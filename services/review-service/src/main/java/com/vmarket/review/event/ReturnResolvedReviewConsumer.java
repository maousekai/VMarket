package com.vmarket.review.event;

import org.springframework.stereotype.Component;

import com.vmarket.events.EventConsumer;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventType;
import com.vmarket.events.ReturnResolved;
import com.vmarket.review.service.ReturnedOrderService;

import lombok.RequiredArgsConstructor;

/**
 * Order Service chốt yêu cầu trả hàng / hoàn tiền → đánh giá của đơn không còn hiển thị (PBL6-35).
 * Queue {@code review.events.v1}, khai báo ở {@code app.events}.
 */
@Component
@RequiredArgsConstructor
public class ReturnResolvedReviewConsumer implements EventConsumer<ReturnResolved> {

	private final ReturnedOrderService returnedOrders;

	@Override
	public String eventType() {
		return EventType.RETURN_RESOLVED;
	}

	@Override
	public Class<ReturnResolved> payloadType() {
		return ReturnResolved.class;
	}

	@Override
	public void handle(ReturnResolved payload, EventEnvelope envelope) {
		returnedOrders.markReturned(payload.orderId(), payload.returnId());
	}
}
