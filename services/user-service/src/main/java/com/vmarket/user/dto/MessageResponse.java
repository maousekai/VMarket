package com.vmarket.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Response chỉ mang một thông báo cho người dùng. */
@Schema(name = "MessageResponse")
public record MessageResponse(
		@Schema(example = "Đổi mật khẩu thành công") String message) {
}
