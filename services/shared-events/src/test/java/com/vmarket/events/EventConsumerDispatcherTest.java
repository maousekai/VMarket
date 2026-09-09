package com.vmarket.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class EventConsumerDispatcherTest {

	private final EventsJson json = new EventsJson();

	static class RecordingConsumer implements EventConsumer<ProductCreated> {
		final List<ProductCreated> received = new ArrayList<>();

		@Override
		public String eventType() {
			return EventType.PRODUCT_CREATED;
		}

		@Override
		public Class<ProductCreated> payloadType() {
			return ProductCreated.class;
		}

		@Override
		public void handle(ProductCreated payload, EventEnvelope envelope) {
			received.add(payload);
		}
	}

	@Test
	void dispatch_routesToMatchingConsumerWithTypedPayload() {
		RecordingConsumer consumer = new RecordingConsumer();
		EventConsumerRegistry registry = new EventConsumerRegistry(List.of(consumer));
		EventConsumerDispatcher dispatcher = new EventConsumerDispatcher(registry, json);

		// Mô phỏng đúng luồng thật: serialize → đọc lại (payload thành Map) → dispatch
		EventEnvelope envelope = new EventEnvelope("evt", EventType.PRODUCT_CREATED, 0L,
				new ProductCreated("p9", "s9", "Quần", java.math.BigDecimal.valueOf(200000), "ACTIVE", List.of()));
		EventEnvelope received = json.readEnvelope(json.write(envelope));

		dispatcher.dispatch(received);

		assertThat(consumer.received).hasSize(1);
		assertThat(consumer.received.get(0).productId()).isEqualTo("p9");
		assertThat(consumer.received.get(0).name()).isEqualTo("Quần");
	}

	@Test
	void dispatch_ignoresUnmatchedEventType() {
		RecordingConsumer consumer = new RecordingConsumer();
		EventConsumerRegistry registry = new EventConsumerRegistry(List.of(consumer));
		EventConsumerDispatcher dispatcher = new EventConsumerDispatcher(registry, json);

		EventEnvelope other = new EventEnvelope("evt", "OrderPlaced", 0L, java.util.Map.of());
		dispatcher.dispatch(other);

		assertThat(consumer.received).isEmpty();
	}
}