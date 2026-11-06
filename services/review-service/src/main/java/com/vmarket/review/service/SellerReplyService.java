package com.vmarket.review.service;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.vmarket.review.client.ReviewClients;
import com.vmarket.review.dto.PageResponse;
import com.vmarket.review.dto.ReviewResponse;
import com.vmarket.review.entity.Review;
import com.vmarket.review.entity.ReviewStatus;
import com.vmarket.review.entity.SellerReply;
import com.vmarket.review.exception.ApiException;
import com.vmarket.review.repository.ReviewRepository;

/**
 * Seller xem và phản hồi công khai đánh giá trong gian hàng mình (FR-REV-03).
 *
 * <p>Gian hàng của seller lấy từ Shop Service bằng chính token của họ (ngoài transaction), rồi so
 * với {@code shopId} chụp từ đơn lúc tạo đánh giá — seller không thể phản hồi đánh giá của shop khác.
 * Chỉ gian hàng {@code ACTIVE} mới được phản hồi/xoá phản hồi; shop bị đình chỉ, từ chối hay chờ duyệt
 * vẫn xem được danh sách đánh giá nhưng không ghi được.
 */
@Service
public class SellerReplyService {

	private final ReviewRepository reviews;
	private final ReviewClients clients;
	private final Clock clock;
	private final TransactionTemplate transactions;

	public SellerReplyService(ReviewRepository reviews, ReviewClients clients, Clock clock,
			PlatformTransactionManager transactionManager) {
		this.reviews = reviews;
		this.clients = clients;
		this.clock = clock;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	public PageResponse<ReviewResponse> myShopReviews(String authorization, ReviewStatus status, Integer rating,
			int page, int size) {
		ReviewService.validateRating(rating);
		var pageable = ReviewService.newestFirst(page, size);
		String shopId = clients.myShop(authorization).id();
		return transactions.execute(tx -> PageResponse.of(reviews.findByShop(shopId, status, rating, pageable),
				ReviewResponse::sellerView));
	}

	public ReviewResponse reply(String sellerId, String authorization, String reviewId, String content) {
		String shopId = activeShopId(authorization);
		return transactions.execute(tx -> {
			Review review = ownedReview(reviewId, shopId);
			if (!review.isVisible()) {
				throw ApiException.conflict("REVIEW_HIDDEN", "Đánh giá đã bị ẩn, không thể phản hồi");
			}
			String text = content.strip();
			if (review.getReply() == null) {
				review.setReply(new SellerReply(review, sellerId, text, clock.instant()));
			} else {
				review.getReply().edit(sellerId, text, clock.instant());
			}
			return ReviewResponse.sellerView(reviews.saveAndFlush(review));
		});
	}

	public void deleteReply(String authorization, String reviewId) {
		String shopId = activeShopId(authorization);
		transactions.executeWithoutResult(tx -> {
			Review review = ownedReview(reviewId, shopId);
			if (review.getReply() == null) {
				throw ApiException.notFound("REPLY_NOT_FOUND", "Đánh giá chưa có phản hồi");
			}
			review.setReply(null);
			reviews.saveAndFlush(review);
		});
	}

	private String activeShopId(String authorization) {
		ReviewClients.MyShop shop = clients.myShop(authorization);
		if (!shop.isActive()) {
			throw ApiException.forbidden("SHOP_NOT_ACTIVE", "Gian hàng chưa hoạt động hoặc đang bị đình chỉ");
		}
		return shop.id();
	}

	/** Đánh giá của shop khác trả 404 như không tồn tại — không lộ đánh giá ngoài phạm vi seller. */
	private Review ownedReview(String reviewId, String shopId) {
		return reviews.findById(reviewId)
				.filter(r -> r.getShopId().equals(shopId))
				.orElseThrow(() -> ApiException.notFound("REVIEW_NOT_FOUND", "Không tìm thấy đánh giá"));
	}
}
