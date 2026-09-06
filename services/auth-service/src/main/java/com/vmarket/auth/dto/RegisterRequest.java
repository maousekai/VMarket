package com.vmarket.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Yêu cầu đăng ký tài khoản (FR-AUTH-01).
 */
@Schema(name = "RegisterRequest")
public record RegisterRequest(

		@Schema(example = "an.nguyen@example.com")
		@NotBlank(message = "Email không được để trống")
		@Email(message = "Email không đúng định dạng")
		@Size(max = 320, message = "Email tối đa 320 ký tự")
		String email,

		@Schema(example = "an.nguyen")
		@NotBlank(message = "Username không được để trống")
		@Pattern(regexp = "^[a-zA-Z0-9._-]{3,50}$",
				message = "Username 3–50 ký tự, chỉ gồm chữ, số và . _ -")
		String username,

		@Schema(example = "Abcd1234@", description = "8–32 ký tự, ít nhất 1 chữ hoa, 1 số và 1 ký tự đặc biệt")
		@NotBlank(message = "Mật khẩu không được để trống")
		@Pattern(regexp = "^(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,32}$",
				message = "Mật khẩu phải 8–32 ký tự, có ít nhất 1 chữ hoa, 1 số và 1 ký tự đặc biệt")
		String password) {
}
