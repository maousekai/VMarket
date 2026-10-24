package com.vmarket.product;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Sinh access token thật cho test, ký bằng khoá trong
 * {@code src/test/resources/application.yml} với cùng issuer/claim như auth-service.
 * Không dùng {@code @WithMockUser} để test đi qua cả {@code JwtAuthenticationFilter}.
 */
final class TestTokens {
	/** PHẢI trùng {@code auth.jwt.secret} trong src/test/resources/application.yml. */
	static final String SECRET = "test-only-secret-0123456789-0123456789-0123456789";

	private TestTokens() {
	}

	static String bearer(String userId, String... roles) {
		return "Bearer " + token("auth-service", userId, List.of(roles), Instant.now());
	}

	static String bearerWrongIssuer(String userId, String... roles) {
		return "Bearer " + token("someone-else", userId, List.of(roles), Instant.now());
	}

	static String bearerExpired(String userId, String... roles) {
		return "Bearer " + token("auth-service", userId, List.of(roles), Instant.now().minus(2, ChronoUnit.HOURS));
	}

	private static String token(String issuer, String userId, List<String> roles, Instant issuedAt) {
		SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
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
