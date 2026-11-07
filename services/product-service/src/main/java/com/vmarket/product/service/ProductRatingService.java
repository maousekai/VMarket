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

	/**
	 * Đồng bộ aggregate đánh giá từ Review Service.
	 *
	 * <p>Thứ tự bản tin được quyết định bằng {@code revision} (tăng dần theo sản phẩm, cấp dưới khoá
	 * DB bên Review Service), không bằng timestamp envelope: các replica của Review Service có đồng
	 * hồ riêng, lệch hay lùi đồng hồ sẽ khiến aggregate mới mang timestamp nhỏ hơn. Bản tin cũ/phát
	 * lại có revision ≤ revision đã lưu bị bỏ qua. {@code sourceUpdatedAt} chỉ còn để truy vết.
	 *
	 * <p>Bản tin trước khi có revision ({@code revision = 0}) chỉ áp dụng khi sản phẩm chưa từng nhận
	 * bản có revision, theo quy tắc timestamp cũ.
	 */
	@Transactional
	public void synchronize(String productId, double ratingAverage, long ratingCount, long revision,
			Instant sourceUpdatedAt) {
		if (productId == null || productId.isBlank() || !Double.isFinite(ratingAverage)
				|| ratingAverage < 0 || ratingAverage > 5 || ratingCount < 0 || revision < 0
				|| sourceUpdatedAt == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REVIEW_AGGREGATE",
					"Aggregate đánh giá không hợp lệ");
		}
		Product product = repository.findById(productId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND",
						"Không tìm thấy sản phẩm"));
		if (isStale(product, revision, sourceUpdatedAt)) return;
		product.setRatingAverage(ratingAverage);
		product.setRatingCount(ratingCount);
		if (revision > 0) product.setRatingRevision(revision);
		product.setRatingUpdatedAt(sourceUpdatedAt);
		product.setUpdatedAt(Instant.now());
		Product saved = repository.save(product);
		publisher.publishUpdated(eventFactory.updated(saved));
	}

	private static boolean isStale(Product product, long revision, Instant sourceUpdatedAt) {
		Long current = product.getRatingRevision();
		if (revision > 0) return current != null && revision <= current;
		// Bản tin cũ không có revision: bản có revision luôn thắng, còn lại so timestamp như trước.
		return current != null
				|| (product.getRatingUpdatedAt() != null && sourceUpdatedAt.isBefore(product.getRatingUpdatedAt()));
	}
}
