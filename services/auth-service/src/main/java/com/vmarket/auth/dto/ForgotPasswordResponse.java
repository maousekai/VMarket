package com.vmarket.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Kết quả yêu cầu đặt lại mật khẩu. Không tiết lộ email đã đăng ký hay chưa —
 * hình dạng response giống nhau bất kể tài khoản có tồn tại hay không.
 */
@Schema(name = "ForgotPasswordResponse")
public record ForgotPasswordResponse(
		@Schema(description = "Thời hạn mã đặt lại mật khẩu (giây)", example = "300") long expiresInSeconds,
		@Schema(example = "Nếu email đã đăng ký, mã đặt lại mật khẩu đã được gửi tới email") String message) {
}
