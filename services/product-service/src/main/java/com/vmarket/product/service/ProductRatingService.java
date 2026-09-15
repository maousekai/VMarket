package com.vmarket.product.service;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.product.event.ProductEventFactory;
import com.vmarket.product.event.ProductEventPublisher;
import com.vmarket.product.exception.ApiException;
import com.vmarket.product.model.Product;
import com.vmarket.product.repository.ProductRepository;

@Service
public class ProductRatingService {
	private final ProductRepository repository;
	private final ProductEventPublisher publisher;
	private final ProductEventFactory eventFactory;

	public ProductRatingService(ProductRepository repository, ProductEventPublisher publisher,
			ProductEventFactory eventFactory) {
		this.repository = repository;
		this.publisher = publisher;
		this.eventFactory = eventFactory;
	}

	@Transactional
	public void synchronize(String productId, double ratingAverage, long ratingCount, Instant sourceUpdatedAt) {
		if (productId == null || productId.isBlank() || !Double.isFinite(ratingAverage)
				|| ratingAverage < 0 || ratingAverage > 5 || ratingCount < 0 || sourceUpdatedAt == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REVIEW_AGGREGATE",
					"Aggregate đánh giá không hợp lệ");
		}
		Product product = repository.findById(productId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND",
						"Không tìm thấy sản phẩm"));
		if (product.getRatingUpdatedAt() != null && sourceUpdatedAt.isBefore(product.getRatingUpdatedAt())) return;
		product.setRatingAverage(ratingAverage);
		product.setRatingCount(ratingCount);
		product.setRatingUpdatedAt(sourceUpdatedAt);
		product.setUpdatedAt(Instant.now());
		Product saved = repository.save(product);
		publisher.publishUpdated(eventFactory.updated(saved));
	}
}
