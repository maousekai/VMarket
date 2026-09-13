package com.vmarket.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

class EventsJsonTest {

	private final EventsJson json = new EventsJson();

	@Test
	void envelope_roundTrip_preservesFieldsAndPayload() {
		ProductCreated payload = new ProductCreated("01P", "01S", "Áo thun", new BigDecimal("100000"),
				"ACTIVE", List.of("https://cdn.vmarket/img/1.jpg"));

		EventEnvelope envelope = new EventEnvelope("evt-1", EventType.PRODUCT_CREATED, 1730000000000L, payload);

		byte[] body = json.write(envelope);
		EventEnvelope parsed = json.readEnvelope(body);

		assertThat(parsed.eventId()).isEqualTo("evt-1");
		assertThat(parsed.eventType()).isEqualTo("ProductCreated");
		assertThat(parsed.timestamp()).isEqualTo(1730000000000L);

		// payload trên dây được đọc dạng khái quát, sau đó convert về kiểu cụ thể
		ProductCreated converted = json.convert(parsed.payload(), ProductCreated.class);
		assertThat(converted.productId()).isEqualTo("01P");
		assertThat(converted.shopId()).isEqualTo("01S");
		assertThat(converted.name()).isEqualTo("Áo thun");
		assertThat(converted.price()).isEqualByComparingTo("100000");
		assertThat(converted.status()).isEqualTo("ACTIVE");
		assertThat(converted.imageUrls()).containsExactly("https://cdn.vmarket/img/1.jpg");
	}

	@Test
	void body_containsExpectedJsonShape() {
		EventEnvelope envelope = new EventEnvelope("evt-2", "ProductCreated", 1730000000000L,
				new ProductCreated("p", "s", "Tên", null, "ACTIVE", List.of()));

		String raw = new String(json.write(envelope), StandardCharsets.UTF_8);

		assertThat(raw).contains("\"eventId\":\"evt-2\"");
		assertThat(raw).contains("\"eventType\":\"ProductCreated\"");
		assertThat(raw).contains("\"timestamp\":1730000000000");
		assertThat(raw).contains("\"payload\":");
	}

	@Test
	void readEnvelope_malformedBody_throwsEventBusException() {
		assertThatThrownBy(() -> json.readEnvelope("not-json".getBytes(StandardCharsets.UTF_8)))
				.isInstanceOf(EventBusException.class);
	}
}