package com.vmarket.review.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vmarket.review.entity.Review;
import com.vmarket.review.entity.ReviewImage;
import com.vmarket.review.entity.SellerReply;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Một đánh giá trả ra client. Trường {@code null} bị lược khỏi JSON; mỗi góc nhìn chỉ lộ đúng dữ liệu
 * người xem cần:
 *
 * <ul>
 * <li>{@link #publicView} — trang sản phẩm/gian hàng (ai cũng xem được): KHÔNG có {@code buyerId},
 * {@code orderId}, {@code variantId} để không ai thu thập được id nội bộ hay liên kết một người với
 * những gì họ đã mua; không có trường kiểm duyệt.</li>
 * <li>{@link #sellerView} — seller của gian hàng: có {@code orderId} (đơn của chính shop) và trường
 * kiểm duyệt, nhưng không có {@code buyerId}.</li>
 * <li>{@link #fullView} — admin và chính người viết.</li>
 * </ul>
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
		return of(r, false, false, false);
	}

	/** Góc nhìn của seller sở hữu gian hàng. */
	public static ReviewResponse sellerView(Review r) {
		return of(r, true, false, true);
	}

	/** Góc nhìn đầy đủ cho admin và chính người viết. */
	public static ReviewResponse fullView(Review r) {
		return of(r, true, true, true);
	}

	private static ReviewResponse of(Review r, boolean withOrder, boolean withBuyer, boolean withModeration) {
		return new ReviewResponse(r.getId(), withOrder ? r.getOrderId() : null, r.getProductId(),
				withOrder ? r.getVariantId() : null, r.getShopId(), withBuyer ? r.getBuyerId() : null, r.getRating(),
				r.getComment(), r.getImages().stream().map(ReviewImage::getUrl).toList(), r.getStatus().name(),
				r.getCreatedAt(), r.getUpdatedAt(), ReplyResponse.from(r.getReply()),
				withModeration ? r.getModerationReason() : null, withModeration ? r.getModeratedAt() : null);
	}
}
