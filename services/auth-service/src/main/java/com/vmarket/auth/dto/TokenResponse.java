package com.vmarket.auth.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Kết quả đăng nhập / refresh: access token + thông tin phiên.
 *
 * <p>Mặc định refresh token KHÔNG nằm trong body này (PBL6-46) — được gắn vào cookie
 * HttpOnly {@code refresh_token} (xem {@code RefreshTokenCookieService}) để JS
 * phía client không đọc được, giảm rủi ro bị đánh cắp qua XSS. Ngoại lệ: client native (mobile) opt-in bằng
 * header {@code X-Client-Type: mobile} và không gửi Origin/Referer → nhận thêm
 * {@code refreshToken}/{@code refreshExpiresIn}.
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
		@Schema(example = "[\"BUYER\"]") List<String> roles,
		@Schema(description = "Chỉ có khi client native opt-in (X-Client-Type: mobile, không Origin/Referer)")
		@JsonInclude(JsonInclude.Include.NON_NULL) String refreshToken,
		@Schema(description = "Thời hạn refresh token (giây); đi kèm refreshToken")
		@JsonInclude(JsonInclude.Include.NON_NULL) Long refreshExpiresIn) {

	public static TokenResponse bearer(String accessToken, long expiresIn, AccountStatus status, List<String> roles) {
		return new TokenResponse(accessToken, "Bearer", expiresIn, status, roles, null, null);
	}

	/** Bản sao có kèm refresh token trong body (chỉ cho client native — xem RefreshTokenCookieService). */
	public TokenResponse withRefreshToken(String rawRefreshToken, long refreshExpiresInSeconds) {
		return new TokenResponse(accessToken, tokenType, expiresIn, status, roles, rawRefreshToken,
				refreshExpiresInSeconds);
	}
}
