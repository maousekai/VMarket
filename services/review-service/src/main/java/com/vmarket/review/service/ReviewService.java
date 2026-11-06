package com.vmarket.review.service;

import java.net.URI;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.vmarket.review.client.ReviewClients;
import com.vmarket.review.config.ReviewProperties;
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
 *
 * <p>SRS gọi trạng thái này là "Hoàn thành"; Order Service hiện chưa có trạng thái hoàn thành riêng
 * (hay tự chuyển sau N ngày) nên dùng {@code DELIVERED}. Khi Order Service bổ sung thì đổi
 * {@link #COMPLETED_STATUS}.
 */
@Service
public class ReviewService {

	/** Trạng thái "Hoàn thành" của đơn bên Order Service ({@code OrderStatus.DELIVERED}). */
	static final String COMPLETED_STATUS = "DELIVERED";
	static final int MAX_PAGE_SIZE = 50;

	private final ReviewRepository reviews;
	private final ReviewClients clients;
	private final ReviewOutbox outbox;
	private final ReviewProperties properties;
	private final Clock clock;
	private final TransactionTemplate transactions;

	public ReviewService(ReviewRepository reviews, ReviewClients clients, ReviewOutbox outbox,
			ReviewProperties properties, Clock clock, PlatformTransactionManager transactionManager) {
		this.reviews = reviews;
		this.clients = clients;
		this.outbox = outbox;
		this.properties = properties;
		this.clock = clock;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	public ReviewResponse create(String buyerId, String authorization, ReviewRequests.CreateReview request) {
		List<String> images = normalizeImages(request.imageUrls());

		// Xác minh quyền sở hữu đơn TRƯỚC khi kiểm tra trùng: người khác đoán đúng orderId nhận 404 như
		// đơn không tồn tại, không biết được đơn đó đã có đánh giá hay chưa.
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
		if (reviews.existsByOrderIdAndProductId(request.orderId(), request.productId())) {
			throw alreadyReviewed();
		}

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
	public PageResponse<ReviewResponse> myReviews(String buyerId, String orderId, int page, int size) {
		return PageResponse.of(reviews.findByBuyer(buyerId, blankToNull(orderId), newestFirst(page, size)),
				ReviewResponse::fullView);
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

	/** Bỏ ảnh trùng (giữ thứ tự người dùng chọn) và chỉ nhận ảnh thuộc origin kho ảnh đã cấu hình. */
	private List<String> normalizeImages(List<String> urls) {
		if (urls == null) {
			return List.of();
		}
		List<String> images = List.copyOf(new LinkedHashSet<>(urls.stream().map(String::strip).toList()));
		for (String url : images) {
			if (!isAllowedImage(url)) {
				throw ApiException.badRequest("IMAGE_URL_NOT_ALLOWED", "Ảnh phải được tải lên kho ảnh của VMarket");
			}
		}
		return images;
	}

	/** So khớp chính xác scheme + host + port với một origin cho phép và có đường dẫn tới file. */
	private boolean isAllowedImage(String url) {
		try {
			URI uri = URI.create(url);
			if (uri.getScheme() == null || uri.getRawUserInfo() != null || uri.getHost() == null || uri.getRawPath() == null
					|| uri.getRawPath().length() <= 1) {
				return false;
			}
			String origin = origin(uri);
			return properties.getImageOrigins().stream().anyMatch(o -> origin.equals(origin(URI.create(o))));
		} catch (IllegalArgumentException ex) {
			return false;
		}
	}

	private static String origin(URI uri) {
		String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
		int port = uri.getPort() != -1 ? uri.getPort() : "https".equals(scheme) ? 443 : 80;
		return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
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
