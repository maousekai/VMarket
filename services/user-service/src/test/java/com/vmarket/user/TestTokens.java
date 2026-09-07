package com.vmarket.user;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Sinh access token thật cho test.
 *
 * <p>Cố ý <b>không</b> dùng {@code @WithMockUser}: mục tiêu là kiểm tra cả
 * {@code JwtAuthenticationFilter} — đọc header, verify chữ ký HS256, lấy
 * {@code sub} làm principal và ánh xạ claim {@code roles} thành authority. Giả lập
 * SecurityContext sẽ nhảy qua đúng phần dễ sai nhất và test vẫn xanh kể cả khi
 * filter hỏng.
 *
 * <p>Ký bằng chính khoá trong {@code src/test/resources/application.yml}, giống
 * hệt cách auth-service sẽ ký ở PBL6-43.
 */
final class TestTokens {

	private TestTokens() {
	}

	static String accessToken(String secret, String userId, List<String> roles) {
		SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
		Instant now = Instant.now();
		return Jwts.builder()
				.subject(userId)
				.claim("roles", roles)
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plus(15, ChronoUnit.MINUTES)))
				.signWith(key)
				.compact();
	}

	/** Token đã hết hạn — dùng để kiểm tra filter thật sự kiểm tra {@code exp}. */
	static String expiredToken(String secret, String userId) {
		SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
		Instant past = Instant.now().minus(2, ChronoUnit.HOURS);
		return Jwts.builder()
				.subject(userId)
				.claim("roles", List.of("BUYER"))
				.issuedAt(Date.from(past))
				.expiration(Date.from(past.plus(15, ChronoUnit.MINUTES)))
				.signWith(key)
				.compact();
	}

	static String bearer(String token) {
		return "Bearer " + token;
	}
}
