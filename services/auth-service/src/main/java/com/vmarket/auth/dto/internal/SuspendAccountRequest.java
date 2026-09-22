package com.vmarket.auth.dto.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin khoá tài khoản (FR-USER-04). Admin thực hiện đi trong header {@code X-Actor-Id}
 * như mọi API quản trị nội bộ khác, không nằm trong body.
 */
@Schema(name = "InternalSuspendAccountRequest")
public record SuspendAccountRequest(

		@Schema(example = "Spam đánh giá sản phẩm nhiều lần")
		@NotBlank(message = "Lý do khoá không được để trống")
		@Size(max = 500, message = "Lý do khoá tối đa 500 ký tự")
		String reason) {
}
