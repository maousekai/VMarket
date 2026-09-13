package com.vmarket.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Kết quả yêu cầu OTP. Không tiết lộ email đã đăng ký hay chưa.
 */
@Schema(name = "OtpRequestResponse")
public record OtpRequestResponse(
		@Schema(description = "Thời hạn mã OTP (giây)", example = "300") long expiresInSeconds,
		@Schema(example = "Mã OTP đã được gửi tới email") String message) {
}
