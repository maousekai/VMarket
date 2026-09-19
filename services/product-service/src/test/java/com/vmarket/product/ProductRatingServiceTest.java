package com.vmarket.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.vmarket.product.event.ProductEventFactory;
import com.vmarket.product.event.ProductEventPublisher;
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

	@BeforeEach
	void setUp() {
		service = new ProductRatingService(repository, publisher, new ProductEventFactory());
	}

	@Test
	void synchronizesLatestAggregateAndPublishesCatalogSnapshot() {
		Product product = product();
		when(repository.findById("p1")).thenReturn(Optional.of(product));
		when(repository.save(product)).thenReturn(product);
		Instant sourceTime = Instant.parse("2026-09-14T10:00:00Z");

		service.synchronize("p1", 4.5, 12, sourceTime);

		assertThat(product.getRatingAverage()).isEqualTo(4.5);
		assertThat(product.getRatingCount()).isEqualTo(12);
		assertThat(product.getRatingUpdatedAt()).isEqualTo(sourceTime);
		verify(publisher).publishUpdated(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void ignoresAggregateOlderThanCurrentProjection() {
		Product product = product();
		product.setRatingUpdatedAt(Instant.parse("2026-09-14T10:00:00Z"));
		when(repository.findById("p1")).thenReturn(Optional.of(product));

		service.synchronize("p1", 1, 1, Instant.parse("2026-09-14T09:00:00Z"));

		verify(repository, never()).save(product);
	}

	@Test
	void rejectsInvalidReviewAggregate() {
		assertThatThrownBy(() -> service.synchronize("p1", 6, -1, Instant.now()))
				.isInstanceOf(ApiException.class).hasMessageContaining("không hợp lệ");
		assertThatThrownBy(() -> service.synchronize("p1", 4, 1, null))
				.isInstanceOf(ApiException.class).hasMessageContaining("không hợp lệ");
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
