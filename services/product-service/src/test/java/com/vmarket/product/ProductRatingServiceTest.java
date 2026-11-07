package com.vmarket.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventType;
import com.vmarket.events.EventsJson;
import com.vmarket.events.ReviewCreated;
import com.vmarket.product.event.ProductEventFactory;
import com.vmarket.product.event.ProductEventPublisher;
import com.vmarket.product.event.ReviewCreatedCatalogConsumer;
import com.vmarket.product.exception.ApiException;
import com.vmarket.product.model.Product;
import com.vmarket.product.model.ProductStatus;
import com.vmarket.product.repository.ProductRepository;
import com.vmarket.product.service.ProductRatingService;

@ExtendWith(MockitoExtension.class)
class ProductRatingServiceTest {
	@Mock ProductRepository repository;
	@Mock ProductEventPublisher publisher;
	private ProductRatingService service;
	private Product product;

	@BeforeEach
	void setUp() {
		service = new ProductRatingService(repository, publisher, new ProductEventFactory());
		product = product();
		lenient().when(repository.findById("p1")).thenReturn(Optional.of(product));
		lenient().when(repository.save(product)).thenReturn(product);
	}

	@Test
	void synchronizesLatestAggregateAndPublishesCatalogSnapshot() {
		Instant sourceTime = Instant.parse("2026-09-14T10:00:00Z");

		service.synchronize("p1", 4.5, 12, 3, sourceTime);

		assertThat(product.getRatingAverage()).isEqualTo(4.5);
		assertThat(product.getRatingCount()).isEqualTo(12);
		assertThat(product.getRatingRevision()).isEqualTo(3);
		assertThat(product.getRatingUpdatedAt()).isEqualTo(sourceTime);
		verify(publisher).publishUpdated(any());
	}

	@Test
	void newerRevisionWinsEvenWhenItsTimestampIsOlder() {
		// Replica A đồng hồ nhanh phát revision 1 lúc 2000; replica B đồng hồ chậm phát revision 2 lúc 1900.
		service.synchronize("p1", 5, 1, 1, Instant.ofEpochMilli(2000));
		service.synchronize("p1", 3.5, 2, 2, Instant.ofEpochMilli(1900));

		assertThat(product.getRatingCount()).isEqualTo(2);
		assertThat(product.getRatingAverage()).isEqualTo(3.5);
		assertThat(product.getRatingRevision()).isEqualTo(2);
	}

	@Test
	void olderOrReplayedRevisionIsIgnoredEvenWithNewerTimestamp() {
		product.setRatingRevision(5L);
		product.setRatingCount(7);

		service.synchronize("p1", 1, 1, 4, Instant.parse("2030-01-01T00:00:00Z"));
		service.synchronize("p1", 1, 1, 5, Instant.parse("2030-01-01T00:00:00Z"));

		assertThat(product.getRatingCount()).isEqualTo(7);
		verify(repository, never()).save(product);
	}

	@Test
	void legacyMessageWithoutRevisionNeverOverridesVersionedProjection() {
		product.setRatingRevision(1L);
		product.setRatingCount(3);

		service.synchronize("p1", 1, 1, 0, Instant.parse("2030-01-01T00:00:00Z"));

		assertThat(product.getRatingCount()).isEqualTo(3);
		verify(repository, never()).save(product);
	}

	@Test
	void legacyMessageWithoutRevisionStillUsesTimestampOrdering() {
		product.setRatingUpdatedAt(Instant.parse("2026-09-14T10:00:00Z"));

		service.synchronize("p1", 1, 1, 0, Instant.parse("2026-09-14T09:00:00Z"));
		verify(repository, never()).save(product);

		service.synchronize("p1", 2, 4, 0, Instant.parse("2026-09-14T11:00:00Z"));
		assertThat(product.getRatingCount()).isEqualTo(4);
		assertThat(product.getRatingRevision()).isNull();
	}

	@Test
	void rejectsInvalidReviewAggregate() {
		assertThatThrownBy(() -> service.synchronize("p1", 6, -1, 1, Instant.now()))
				.isInstanceOf(ApiException.class).hasMessageContaining("không hợp lệ");
		assertThatThrownBy(() -> service.synchronize("p1", 4, 1, 1, null))
				.isInstanceOf(ApiException.class).hasMessageContaining("không hợp lệ");
		assertThatThrownBy(() -> service.synchronize("p1", 4, 1, -1, Instant.now()))
				.isInstanceOf(ApiException.class).hasMessageContaining("không hợp lệ");
	}

	/** Đường thật producer → envelope JSON → consumer, với đồng hồ hai replica lệch nhau. */
	@Test
	void consumerAppliesRevisionOrderThroughSerializedEnvelopes() {
		EventsJson json = new EventsJson();
		var consumer = new ReviewCreatedCatalogConsumer(service);

		deliver(json, consumer, new EventEnvelope("e1", EventType.REVIEW_CREATED, 2000,
				new ReviewCreated("r1", "p1", 5.0, 1, 1L)));
		deliver(json, consumer, new EventEnvelope("e2", EventType.REVIEW_CREATED, 1900,
				new ReviewCreated("r2", "p1", 4.0, 2, 2L)));
		// Phát lại bản cũ (ví dụ admin replay outbox) sau đó: không ghi đè.
		deliver(json, consumer, new EventEnvelope("e1-replay", EventType.REVIEW_CREATED, 3000,
				new ReviewCreated("r1", "p1", 5.0, 1, 1L)));

		assertThat(product.getRatingCount()).isEqualTo(2);
		assertThat(product.getRatingAverage()).isEqualTo(4.0);
		assertThat(product.getRatingRevision()).isEqualTo(2);
	}

	@Test
	void legacyPayloadWithoutRevisionFieldDeserializesAsZero() {
		EventsJson json = new EventsJson();
		String legacy = """
				{"eventId":"old","eventType":"ReviewCreated","timestamp":1000,
				 "payload":{"reviewId":"r0","productId":"p1","ratingAverage":4.0,"ratingCount":1}}""";
		EventEnvelope envelope = json.readEnvelope(legacy.getBytes(StandardCharsets.UTF_8));

		assertThat(json.convert(envelope.payload(), ReviewCreated.class).revision()).isZero();
	}

	private static void deliver(EventsJson json, ReviewCreatedCatalogConsumer consumer, EventEnvelope sent) {
		EventEnvelope received = json.readEnvelope(json.write(sent));
		consumer.handle(json.convert(received.payload(), consumer.payloadType()), received);
	}

	private Product product() {
		Product product = new Product();
		product.setId("p1");
		product.setName("Tên");
		product.setStatus(ProductStatus.ACTIVE);
		product.setCategoryVisible(true);
		return product;
	}
}
