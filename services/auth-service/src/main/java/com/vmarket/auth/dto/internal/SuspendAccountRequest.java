package com.vmarket.auth.dto.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Admin khoá tài khoản (FR-USER-04). */
@Schema(name = "InternalSuspendAccountRequest")
public record SuspendAccountRequest(

		@Schema(example = "Spam đánh giá sản phẩm nhiều lần")
		@NotBlank(message = "Lý do khoá không được để trống")
		@Size(max = 500, message = "Lý do khoá tối đa 500 ký tự")
		String reason,

		@Schema(example = "01JBQ9YDX7K3M8N5P2R4T6V8AA", description = "userId của Admin thực hiện (audit)")
		@NotBlank(message = "Thiếu userId của Admin thực hiện")
		@Size(max = 26, message = "userId không hợp lệ")
		String actorId) {
}
