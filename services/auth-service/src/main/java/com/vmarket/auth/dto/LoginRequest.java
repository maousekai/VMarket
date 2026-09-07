package com.vmarket.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Yêu cầu đăng nhập (FR-AUTH-02). Đăng nhập bằng email.
 */
@Schema(name = "LoginRequest")
public record LoginRequest(

		@Schema(example = "an.nguyen@example.com")
		@NotBlank(message = "Email không được để trống")
		@Email(message = "Email không đúng định dạng")
		String email,

		@Schema(example = "Abcd1234@")
		@NotBlank(message = "Mật khẩu không được để trống")
		String password) {
}
