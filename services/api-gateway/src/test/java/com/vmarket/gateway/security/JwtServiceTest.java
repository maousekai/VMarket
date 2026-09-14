package com.vmarket.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.vmarket.gateway.config.GatewayJwtProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

class JwtServiceTest {

	private static final String SECRET = "test-only-secret-0123456789-0123456789-0123456789";
	private static final String WRONG_SECRET = "another-secret-0123456789-0123456789-0123456789";

	private JwtService newService() {
		GatewayJwtProperties props = new GatewayJwtProperties();
		props.setSecret(SECRET);
		return new JwtService(props);
	}

	private String build(String secret, String issuer, Instant issuedAt, Instant expiresAt) {
		return Jwts.builder()
				.issuer(issuer)
				.subject("01JRX8Z0M0P8QF3W9K2T7Y6C4B")
				.claim("email", "an@example.com")
				.claim("roles", List.of("BUYER"))
				.issuedAt(Date.from(issuedAt))
				.expiration(Date.from(expiresAt))
				.signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
				.compact();
	}

	@Test
	void parse_validToken_returnsClaims() {
		String token = build(SECRET, "auth-service", Instant.now(), Instant.now().plusSeconds(60));
		Claims claims = newService().parse(token);
		assertThat(claims.getSubject()).isEqualTo("01JRX8Z0M0P8QF3W9K2T7Y6C4B");
		assertThat(claims.getIssuer()).isEqualTo("auth-service");
		assertThat(claims.get("roles", List.class)).containsExactly("BUYER");
	}

	@Test
	void parse_wrongSecret_throws() {
		String token = build(WRONG_SECRET, "auth-service", Instant.now(), Instant.now().plusSeconds(60));
		assertThatThrownBy(() -> newService().parse(token)).isInstanceOf(JwtException.class);
	}

	@Test
	void parse_wrongIssuer_throws() {
		String token = build(SECRET, "other-service", Instant.now(), Instant.now().plusSeconds(60));
		assertThatThrownBy(() -> newService().parse(token)).isInstanceOf(JwtException.class);
	}

	@Test
	void parse_expiredToken_throws() {
		String token = build(SECRET, "auth-service", Instant.now().minusSeconds(120), Instant.now().minusSeconds(60));
		assertThatThrownBy(() -> newService().parse(token)).isInstanceOf(JwtException.class);
	}
}