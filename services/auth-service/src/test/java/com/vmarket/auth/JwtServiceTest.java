package com.vmarket.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.vmarket.auth.config.AuthJwtProperties;
import com.vmarket.auth.entity.User;
import com.vmarket.auth.security.JwtService;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

class JwtServiceTest {

	private static final String SECRET = "test-only-secret-0123456789-0123456789-0123456789";

	private JwtService newService() {
		AuthJwtProperties props = new AuthJwtProperties();
		props.setSecret(SECRET);
		props.setAccessTtl(Duration.ofMinutes(15));
		props.setRefreshTtl(Duration.ofDays(15));
		return new JwtService(props);
	}

	@Test
	void accessToken_carriesExpectedClaims() {
		User user = new User();
		user.setId("01JRX8Z0M0P8QF3W9K2T7Y6C4B");
		user.setEmail("an@example.com");
		user.setUsername("an.nguyen");
		user.setEmailVerified(false);

		String token = newService().createAccessToken(user, List.of("BUYER"));

		Claims claims = Jwts.parser()
				.verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
				.build()
				.parseSignedClaims(token)
				.getPayload();

		assertThat(claims.getSubject()).isEqualTo("01JRX8Z0M0P8QF3W9K2T7Y6C4B");
		assertThat(claims.getIssuer()).isEqualTo("auth-service");
		assertThat(claims.get("email")).isEqualTo("an@example.com");
		assertThat(claims.get("username")).isEqualTo("an.nguyen");
		assertThat(claims.get("email_verified")).isEqualTo(false);
		assertThat(claims.get("roles", List.class)).containsExactly("BUYER");
		assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
	}

	@Test
	void accessToken_signatureRejectedWithWrongSecret() {
		User user = new User();
		user.setId("01JRX8Z0M0P8QF3W9K2T7Y6C4B");
		user.setEmail("a@b.com");
		user.setUsername("ab");
		String token = newService().createAccessToken(user, List.of("BUYER"));

		var wrongKey = Keys.hmacShaKeyFor("another-secret-0123456789-0123456789-0123456789".getBytes(StandardCharsets.UTF_8));
		org.assertj.core.api.Assertions.assertThatThrownBy(() ->
				Jwts.parser().verifyWith(wrongKey).build().parseSignedClaims(token));
	}
}
