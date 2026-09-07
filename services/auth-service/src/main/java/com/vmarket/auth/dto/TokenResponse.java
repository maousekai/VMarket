package com.vmarket.auth.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Kết quả đăng nhập / refresh: cặp token + thông tin phiên.
 *
 * <p>{@code status = PENDING} → client hiển thị màn hình "chưa xác thực email",
 * chưa cho vào hệ thống bình thường.
 */
@Schema(name = "TokenResponse")
public record TokenResponse(
		@Schema(example = "eyJhbGciOiJIUzI1NiJ9...") String accessToken,
		@Schema(example = "e30cJ1s...") String refreshToken,
		@Schema(example = "Bearer") String tokenType,
		@Schema(description = "Thời hạn access token (giây)", example = "900") long expiresIn,
		@Schema(example = "PENDING") AccountStatus status,
		@Schema(example = "[\"BUYER\"]") List<String> roles) {

	public static TokenResponse bearer(String accessToken, String refreshToken, long expiresIn,
			AccountStatus status, List<String> roles) {
		return new TokenResponse(accessToken, refreshToken, "Bearer", expiresIn, status, roles);
	}
}
