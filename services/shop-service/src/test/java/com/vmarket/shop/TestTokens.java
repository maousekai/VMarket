package com.vmarket.shop;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Sinh access token thật cho test — cùng khuôn với {@code TestTokens} của user-service.
 *
 * <p>Cố ý <b>không</b> dùng {@code @WithMockUser}: mục tiêu là kiểm tra cả
 * {@code JwtAuthenticationFilter} (verify chữ ký, lấy {@code sub}, ánh xạ {@code roles})
 * lẫn các rule phân quyền theo URL của {@code SecurityConfig}.
 */
final class TestTokens {

	static final String ISSUER = "auth-service";

	private TestTokens() {
	}

	static String accessToken(String secret, String userId, List<String> roles) {
		return build(secret, ISSUER, userId, roles, Instant.now());
	}

	/** Token đúng chữ ký nhưng không do auth-service phát hành — phải bị từ chối. */
	static String wrongIssuerToken(String secret, String userId) {
		return build(secret, "someone-else", userId, List.of("ADMIN"), Instant.now());
	}

	/** Token đã hết hạn. */
	static String expiredToken(String secret, String userId) {
		return build(secret, ISSUER, userId, List.of("BUYER"), Instant.now().minus(2, ChronoUnit.HOURS));
	}

	static String bearer(String token) {
		return "Bearer " + token;
	}

	private static String build(String secret, String issuer, String userId, List<String> roles, Instant issuedAt) {
		SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
		return Jwts.builder()
				.issuer(issuer)
				.subject(userId)
				.claim("roles", roles)
				.issuedAt(Date.from(issuedAt))
				.expiration(Date.from(issuedAt.plus(15, ChronoUnit.MINUTES)))
				.signWith(key)
				.compact();
	}
}
