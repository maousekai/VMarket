package com.vmarket.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin khoá tài khoản vi phạm (FR-USER-04). Lý do bắt buộc: khoá là quyết định ảnh
 * hưởng trực tiếp tới người dùng, Admin khác cần biết vì sao khi xem lại hoặc khi người
 * dùng khiếu nại.
 */
@Schema(name = "LockUserRequest")
public record LockUserRequest(

		@Schema(example = "Spam đánh giá sản phẩm nhiều lần")
		@NotBlank(message = "Lý do khoá không được để trống")
		@Size(max = 500, message = "Lý do khoá tối đa 500 ký tự")
		String reason) {
}
