package com.vmarket.auth.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Kết quả đăng nhập / refresh: access token + thông tin phiên.
 *
 * <p>Refresh token KHÔNG nằm trong body này (PBL6-46) — được gắn vào cookie
 * HttpOnly {@code refresh_token} (xem {@code RefreshTokenCookieService}) để JS
 * phía client không đọc được, giảm rủi ro bị đánh cắp qua XSS.
 *
 * <p>{@code status = PENDING} → client hiển thị màn hình "chưa xác thực email",
 * chưa cho vào hệ thống bình thường.
 */
@Schema(name = "TokenResponse")
public record TokenResponse(
		@Schema(example = "eyJhbGciOiJIUzI1NiJ9...") String accessToken,
		@Schema(example = "Bearer") String tokenType,
		@Schema(description = "Thời hạn access token (giây)", example = "900") long expiresIn,
		@Schema(example = "PENDING") AccountStatus status,
		@Schema(example = "[\"BUYER\"]") List<String> roles) {

	public static TokenResponse bearer(String accessToken, long expiresIn, AccountStatus status, List<String> roles) {
		return new TokenResponse(accessToken, "Bearer", expiresIn, status, roles);
	}
}
