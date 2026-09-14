package com.vmarket.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Xác nhận mã OTP xác thực email (FR-AUTH-01). */
@Schema(name = "OtpVerify")
public record OtpVerifyDto(

		@Schema(example = "an.nguyen@example.com")
		@NotBlank(message = "Email không được để trống")
		@Email(message = "Email không đúng định dạng")
		String email,

		@Schema(example = "482913")
		@NotBlank(message = "Mã OTP không được để trống")
		@Pattern(regexp = "^\\d{6}$", message = "Mã OTP gồm 6 chữ số")
		String otp) {
}
