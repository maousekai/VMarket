package com.vmarket.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Yêu cầu đăng nhập bằng username hoặc email. Vẫn nhận trường email từ client cũ.
 */
@Schema(name = "LoginRequest")
public record LoginRequest(

		@Schema(description = "Username (phân biệt hoa/thường) hoặc email", example = "an.nguyen")
		@JsonAlias("email")
		@NotBlank(message = "Tên tài khoản hoặc email không được để trống")
		@Size(max = 320, message = "Tên tài khoản hoặc email tối đa 320 ký tự")
		String identifier,

		@Schema(example = "Abcd1234@")
		@NotBlank(message = "Mật khẩu không được để trống")
		String password) {
}
