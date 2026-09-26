package com.vmarket.cart;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Sinh access token THẬT cho test.
 *
 * <p>Cố ý <b>không</b> dùng {@code @WithMockUser}: mục tiêu là kiểm tra cả
 * {@code CartAuthenticationFilter} — đọc header, verify chữ ký HS256, lấy
 * {@code sub} làm principal, và từ chối token sai issuer/hết hạn. Giả lập
 * SecurityContext sẽ nhảy qua đúng phần dễ sai nhất.
 *
 * <p>Ký bằng chính khoá trong {@code src/test/resources/application.yml}, cùng
 * issuer và claim với {@code com.vmarket.auth.security.JwtService} (PBL6-43).
 */
public final class TestTokens {

	public static final String ISSUER = "auth-service";

	private TestTokens() {
	}

	public static String accessToken(String secret, String userId, List<String> roles) {
		SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
		Instant now = Instant.now();
		return Jwts.builder()
				.issuer(ISSUER)
				.subject(userId)
				.claim("roles", roles)
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plus(15, ChronoUnit.MINUTES)))
				.signWith(key)
				.compact();
	}

	/** Token hết hạn — kiểm tra filter thật sự kiểm tra {@code exp}. */
	public static String expiredToken(String secret, String userId) {
		SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
		Instant past = Instant.now().minus(2, ChronoUnit.HOURS);
		return Jwts.builder()
				.issuer(ISSUER)
				.subject(userId)
				.claim("roles", List.of("BUYER"))
				.issuedAt(Date.from(past))
				.expiration(Date.from(past.plus(15, ChronoUnit.MINUTES)))
				.signWith(key)
				.compact();
	}

	/** Token hợp lệ về chữ ký nhưng KHÔNG do auth-service phát hành — phải bị từ chối. */
	public static String wrongIssuerToken(String secret, String userId) {
		SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
		Instant now = Instant.now();
		return Jwts.builder()
				.issuer("someone-else")
				.subject(userId)
				.claim("roles", List.of("BUYER"))
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plus(15, ChronoUnit.MINUTES)))
				.signWith(key)
				.compact();
	}

	public static String bearer(String token) {
		return "Bearer " + token;
	}
}