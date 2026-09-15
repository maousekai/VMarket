package com.vmarket.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Yêu cầu đặt lại mật khẩu (FR-AUTH-04). */
@Schema(name = "ForgotPasswordRequest")
public record ForgotPasswordRequest(

		@Schema(example = "an.nguyen@example.com")
		@NotBlank(message = "Email không được để trống")
		@Email(message = "Email không đúng định dạng")
		@Size(max = 320, message = "Email tối đa 320 ký tự")
		String email) {
}
