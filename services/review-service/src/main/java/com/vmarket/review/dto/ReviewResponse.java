package com.vmarket.review.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vmarket.review.entity.Review;
import com.vmarket.review.entity.ReviewImage;
import com.vmarket.review.entity.SellerReply;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Một đánh giá trả ra client. Trường kiểm duyệt ({@code moderation*}) chỉ có ở góc nhìn của admin /
 * seller / chính người viết; góc nhìn công khai để {@code null} và bị lược khỏi JSON.
 */
@Schema(name = "ReviewResponse", description = "Đánh giá sản phẩm")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewResponse(
		String id,
		String orderId,
		String productId,
		String variantId,
		String shopId,
		String buyerId,
		@Schema(example = "5") int rating,
		String comment,
		List<String> imageUrls,
		@Schema(example = "VISIBLE") String status,
		Instant createdAt,
		Instant updatedAt,
		ReplyResponse reply,
		String moderationReason,
		Instant moderatedAt) {

	@Schema(name = "SellerReplyResponse")
	public record ReplyResponse(String content, Instant createdAt, Instant updatedAt) {
		static ReplyResponse from(SellerReply reply) {
			return reply == null ? null : new ReplyResponse(reply.getContent(), reply.getCreatedAt(), reply.getUpdatedAt());
		}
	}

	/** Góc nhìn công khai (trang sản phẩm / gian hàng). */
	public static ReviewResponse publicView(Review r) {
		return of(r, false);
	}

	/** Góc nhìn đầy đủ cho admin, seller của gian hàng và người viết. */
	public static ReviewResponse fullView(Review r) {
		return of(r, true);
	}

	private static ReviewResponse of(Review r, boolean full) {
		return new ReviewResponse(r.getId(), r.getOrderId(), r.getProductId(), r.getVariantId(), r.getShopId(),
				r.getBuyerId(), r.getRating(), r.getComment(),
				r.getImages().stream().map(ReviewImage::getUrl).toList(), r.getStatus().name(), r.getCreatedAt(),
				r.getUpdatedAt(), ReplyResponse.from(r.getReply()), full ? r.getModerationReason() : null,
				full ? r.getModeratedAt() : null);
	}
}
