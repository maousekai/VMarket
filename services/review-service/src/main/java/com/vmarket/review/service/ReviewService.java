package com.vmarket.review.service;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.vmarket.review.client.ReviewClients;
import com.vmarket.review.dto.PageResponse;
import com.vmarket.review.dto.ReviewRequests;
import com.vmarket.review.dto.ReviewResponse;
import com.vmarket.review.entity.Review;
import com.vmarket.review.entity.ReviewStatus;
import com.vmarket.review.exception.ApiException;
import com.vmarket.review.repository.ReviewRepository;

import tools.jackson.databind.JsonNode;

/**
 * Viết và đọc đánh giá (FR-REV-01, FR-REV-02).
 *
 * <p>Điều kiện được đánh giá: đơn thuộc về người gọi, đã ở trạng thái hoàn thành
 * ({@value #COMPLETED_STATUS}) và chứa sản phẩm được đánh giá. Gọi Order Service <b>ngoài</b>
 * transaction để không giữ kết nối DB trong lúc chờ mạng; ràng buộc {@code UNIQUE (order_id,
 * product_id)} vẫn chặn được request trùng lọt qua bước kiểm tra trước.
 */
@Service
public class ReviewService {

	/** Trạng thái "Hoàn thành" của đơn bên Order Service ({@code OrderStatus.DELIVERED}). */
	static final String COMPLETED_STATUS = "DELIVERED";
	static final int MAX_PAGE_SIZE = 50;

	private final ReviewRepository reviews;
	private final ReviewClients clients;
	private final ReviewOutbox outbox;
	private final Clock clock;
	private final TransactionTemplate transactions;

	public ReviewService(ReviewRepository reviews, ReviewClients clients, ReviewOutbox outbox, Clock clock,
			PlatformTransactionManager transactionManager) {
		this.reviews = reviews;
		this.clients = clients;
		this.outbox = outbox;
		this.clock = clock;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	public ReviewResponse create(String buyerId, String authorization, ReviewRequests.CreateReview request) {
		List<String> images = normalizeImages(request.imageUrls());
		if (reviews.existsByOrderIdAndProductId(request.orderId(), request.productId())) {
			throw alreadyReviewed();
		}

		JsonNode order = clients.myOrder(request.orderId(), authorization);
		if (!request.orderId().equals(order.path("id").asString())) {
			throw ApiException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng của bạn");
		}
		if (!COMPLETED_STATUS.equals(order.path("status").asString())) {
			throw ApiException.unprocessable("ORDER_NOT_COMPLETED", "Chỉ đánh giá được sản phẩm thuộc đơn đã hoàn thành");
		}
		JsonNode item = findItem(order, request.productId());
		String shopId = item.path("shopId").asString();
		if (shopId.isBlank()) {
			throw ApiException.unavailable("DEPENDENCY_UNAVAILABLE", "Order Service trả dữ liệu không hợp lệ");
		}
		String variantId = item.path("variantId").isString() ? item.path("variantId").asString() : null;
		String comment = request.comment() == null || request.comment().isBlank() ? null : request.comment().strip();

		try {
			return transactions.execute(status -> {
				Review review = reviews.saveAndFlush(Review.create(request.orderId(), request.productId(), variantId,
						shopId, buyerId, request.rating(), comment, images, clock.instant()));
				outbox.ratingChanged(review.getId(), review.getProductId());
				return ReviewResponse.fullView(review);
			});
		} catch (DataIntegrityViolationException ex) {
			// Hai request cùng lúc cho cùng (đơn, sản phẩm): request thua bị UNIQUE chặn.
			throw alreadyReviewed();
		}
	}

	@Transactional(readOnly = true)
	public PageResponse<ReviewResponse> productReviews(String productId, Integer rating, int page, int size) {
		validateRating(rating);
		return PageResponse.of(reviews.findByProduct(productId, ReviewStatus.VISIBLE, rating, newestFirst(page, size)),
				ReviewResponse::publicView);
	}

	@Transactional(readOnly = true)
	public PageResponse<ReviewResponse> shopReviews(String shopId, Integer rating, int page, int size) {
		validateRating(rating);
		return PageResponse.of(reviews.findByShop(shopId, ReviewStatus.VISIBLE, rating, newestFirst(page, size)),
				ReviewResponse::publicView);
	}

	/** Đánh giá của tôi — lọc theo đơn để client biết sản phẩm nào trong đơn đã đánh giá. */
	@Transactional(readOnly = true)
	public List<ReviewResponse> myReviews(String buyerId, String orderId) {
		return reviews.findByBuyer(buyerId, blankToNull(orderId)).stream().map(ReviewResponse::fullView).toList();
	}

	static PageRequest newestFirst(int page, int size) {
		if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
			throw ApiException.badRequest("VALIDATION_ERROR", "page >= 0 và 1 <= size <= " + MAX_PAGE_SIZE);
		}
		return PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
	}

	static void validateRating(Integer rating) {
		if (rating != null && (rating < 1 || rating > 5)) {
			throw ApiException.badRequest("VALIDATION_ERROR", "rating phải từ 1 đến 5");
		}
	}

	static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

	private static List<String> normalizeImages(List<String> urls) {
		if (urls == null) {
			return List.of();
		}
		// Bỏ ảnh trùng nhưng giữ thứ tự người dùng chọn.
		return List.copyOf(new LinkedHashSet<>(urls.stream().map(String::strip).toList()));
	}

	private static JsonNode findItem(JsonNode order, String productId) {
		for (JsonNode item : order.path("items")) {
			if (productId.equals(item.path("productId").asString())) {
				return item;
			}
		}
		throw ApiException.unprocessable("PRODUCT_NOT_IN_ORDER", "Sản phẩm không thuộc đơn hàng này");
	}

	private static ApiException alreadyReviewed() {
		return ApiException.conflict("REVIEW_ALREADY_EXISTS", "Bạn đã đánh giá sản phẩm này trong đơn hàng");
	}
}
