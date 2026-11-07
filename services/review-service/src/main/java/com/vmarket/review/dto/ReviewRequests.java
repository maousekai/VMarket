package com.vmarket.review.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body request của Review Service. */
public final class ReviewRequests {

	private ReviewRequests() {
	}

	/** FR-REV-01: 1–5 sao, bình luận tuỳ chọn, tối đa 5 ảnh (URL đã upload). */
	@Schema(name = "CreateReviewRequest")
	public record CreateReview(
			@Schema(example = "01JC4X9Q2W5R8T6V8N0P4S6A2B")
			@NotBlank(message = "Thiếu mã đơn hàng") @Size(max = 36) String orderId,

			@Schema(example = "6650f1c2a1b2c3d4e5f60718")
			@NotBlank(message = "Thiếu mã sản phẩm") @Size(max = 64) String productId,

			@Schema(example = "5")
			@NotNull(message = "Thiếu số sao")
			@Min(value = 1, message = "Số sao từ 1 đến 5") @Max(value = 5, message = "Số sao từ 1 đến 5") Integer rating,

			@Schema(example = "Hàng đẹp, giao nhanh")
			@Size(max = 2000, message = "Bình luận tối đa 2000 ký tự") String comment,

			@Size(max = 5, message = "Tối đa 5 ảnh")
			List<@NotBlank @Size(max = 1000) @Pattern(regexp = "^https?://\\S+$", message = "URL ảnh không hợp lệ") String> imageUrls) {
	}

	/** FR-REV-03: nội dung phản hồi công khai của seller. */
	@Schema(name = "SellerReplyRequest")
	public record Reply(
			@Schema(example = "Cảm ơn bạn đã ủng hộ shop!")
			@NotBlank(message = "Nội dung phản hồi không được để trống")
			@Size(max = 1000, message = "Phản hồi tối đa 1000 ký tự") String content) {
	}

	/** FR-REV-04: lý do ẩn đánh giá (bắt buộc để lưu vết kiểm duyệt). */
	@Schema(name = "HideReviewRequest")
	public record Hide(
			@Schema(example = "Ngôn từ xúc phạm")
			@NotBlank(message = "Cần nêu lý do ẩn đánh giá")
			@Size(max = 500, message = "Lý do tối đa 500 ký tự") String reason) {
	}
}
