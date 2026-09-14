package com.vmarket.product.event;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.vmarket.events.EventConsumer;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventType;
import com.vmarket.events.ReviewCreated;
import com.vmarket.product.service.ProductRatingService;

@Component
public class ReviewCreatedCatalogConsumer implements EventConsumer<ReviewCreated> {
	private final ProductRatingService ratingService;

	public ReviewCreatedCatalogConsumer(ProductRatingService ratingService) {
		this.ratingService = ratingService;
	}

	@Override public String eventType() { return EventType.REVIEW_CREATED; }
	@Override public Class<ReviewCreated> payloadType() { return ReviewCreated.class; }

	@Override
	public void handle(ReviewCreated payload, EventEnvelope envelope) {
		ratingService.synchronize(payload.productId(), payload.ratingAverage(), payload.ratingCount(),
				Instant.ofEpochMilli(envelope.timestamp()));
	}
}
