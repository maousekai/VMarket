package com.vmarket.review.service;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.review.dto.PageResponse;
import com.vmarket.review.dto.ReviewResponse;
import com.vmarket.review.entity.Review;
import com.vmarket.review.entity.ReviewStatus;
import com.vmarket.review.exception.ApiException;
import com.vmarket.review.repository.ReviewRepository;

import lombok.RequiredArgsConstructor;

/**
 * Admin ẩn / khôi phục đánh giá vi phạm (FR-REV-04).
 *
 * <p>Ẩn hay khôi phục đều làm đổi điểm trung bình của sản phẩm, nên cùng transaction ghi một dòng
 * outbox để Product Service nhận aggregate mới (cùng sự kiện {@code ReviewCreated}, payload luôn là
 * aggregate tuyệt đối nên consumer không cần phân biệt nguyên nhân).
 */
@Service
@RequiredArgsConstructor
public class ModerationService {

	private final ReviewRepository reviews;
	private final ReviewOutbox outbox;
	private final Clock clock;

	@Transactional(readOnly = true)
	public PageResponse<ReviewResponse> search(ReviewStatus status, String productId, String shopId, int page,
			int size) {
		return PageResponse.of(reviews.search(status, ReviewService.blankToNull(productId),
				ReviewService.blankToNull(shopId), ReviewService.newestFirst(page, size)), ReviewResponse::fullView);
	}

	@Transactional
	public ReviewResponse hide(String adminId, String reviewId, String reason) {
		Review review = find(reviewId);
		if (review.getStatus() == ReviewStatus.HIDDEN) {
			throw ApiException.conflict("REVIEW_ALREADY_HIDDEN", "Đánh giá đã bị ẩn");
		}
		return changeStatus(review, ReviewStatus.HIDDEN, adminId, reason.strip());
	}

	@Transactional
	public ReviewResponse restore(String adminId, String reviewId) {
		Review review = find(reviewId);
		if (review.getStatus() == ReviewStatus.VISIBLE) {
			throw ApiException.conflict("REVIEW_NOT_HIDDEN", "Đánh giá đang hiển thị");
		}
		return changeStatus(review, ReviewStatus.VISIBLE, adminId, null);
	}

	private ReviewResponse changeStatus(Review review, ReviewStatus status, String adminId, String reason) {
		var now = clock.instant();
		review.setStatus(status);
		review.setModerationReason(reason);
		review.setModeratedBy(adminId);
		review.setModeratedAt(now);
		review.setUpdatedAt(now);
		Review saved = reviews.saveAndFlush(review);
		outbox.ratingChanged(saved.getId(), saved.getProductId());
		return ReviewResponse.fullView(saved);
	}

	private Review find(String reviewId) {
		return reviews.findById(reviewId)
				.orElseThrow(() -> ApiException.notFound("REVIEW_NOT_FOUND", "Không tìm thấy đánh giá"));
	}
}
