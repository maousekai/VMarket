package com.vmarket.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.vmarket.gateway.config.GatewayJwtProperties;
import com.vmarket.gateway.config.GatewaySecurityProperties;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;

class JwtAuthenticationFilterTest {

	private static final String SECRET = "test-only-secret-0123456789-0123456789-0123456789";

	private JwtAuthenticationFilter filter;

	@BeforeEach
	void setUp() {
		GatewayJwtProperties jwtProps = new GatewayJwtProperties();
		jwtProps.setSecret(SECRET);

		GatewaySecurityProperties security = new GatewaySecurityProperties();
		security.setPublicPaths(List.of("/api/auth/**", "/actuator/**"));
		security.setPublicGetPaths(List.of("/api/products/**"));

		filter = new JwtAuthenticationFilter(new JwtService(jwtProps), security);
	}

	private String token(Instant issuedAt, Instant expiresAt) {
		return Jwts.builder()
				.issuer("auth-service")
				.subject("01JRX8Z0M0P8QF3W9K2T7Y6C4B")
				.claim("email", "an@example.com")
				.claim("username", "an.nguyen")
				.claim("roles", List.of("BUYER", "SELLER"))
				.issuedAt(Date.from(issuedAt))
				.expiration(Date.from(expiresAt))
				.signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
				.compact();
	}

	private MockHttpServletRequest request(String method, String path, String authHeader) {
		MockHttpServletRequest req = new MockHttpServletRequest(method, path);
		if (authHeader != null) {
			req.addHeader(HttpHeaders.AUTHORIZATION, authHeader);
		}
		return req;
	}

	@Test
	void publicPath_withoutToken_passes() throws Exception {
		MockHttpServletResponse res = new MockHttpServletResponse();
		filter.doFilter(request("POST", "/api/auth/login", null), res, new MockFilterChain());
		assertThat(res.getStatus()).isEqualTo(200);
	}

	@Test
	void publicGetPath_getWithoutToken_passes() throws Exception {
		MockHttpServletResponse res = new MockHttpServletResponse();
		filter.doFilter(request("GET", "/api/products/123", null), res, new MockFilterChain());
		assertThat(res.getStatus()).isEqualTo(200);
	}

	@Test
	void publicGetPath_postWithoutToken_rejected() throws Exception {
		MockHttpServletResponse res = new MockHttpServletResponse();
		filter.doFilter(request("POST", "/api/products", null), res, new MockFilterChain());
		assertThat(res.getStatus()).isEqualTo(401);
	}

	@Test
	void protectedPath_withoutToken_rejected() throws Exception {
		MockHttpServletResponse res = new MockHttpServletResponse();
		filter.doFilter(request("GET", "/api/orders/123", null), res, new MockFilterChain());
		assertThat(res.getStatus()).isEqualTo(401);
	}

	@Test
	void protectedPath_withMalformedToken_rejected() throws Exception {
		MockHttpServletResponse res = new MockHttpServletResponse();
		filter.doFilter(request("GET", "/api/orders/123", "Bearer not-a-jwt"), res, new MockFilterChain());
		assertThat(res.getStatus()).isEqualTo(401);
	}

	@Test
	void protectedPath_withExpiredToken_rejected() throws Exception {
		String expired = token(Instant.now().minusSeconds(120), Instant.now().minusSeconds(60));
		MockHttpServletResponse res = new MockHttpServletResponse();
		filter.doFilter(request("GET", "/api/orders/123", "Bearer " + expired), res, new MockFilterChain());
		assertThat(res.getStatus()).isEqualTo(401);
	}

	@Test
	void protectedPath_withValidToken_forwardsIdentityHeaders() throws Exception {
		String valid = token(Instant.now(), Instant.now().plusSeconds(60));

		AtomicReference<HttpServletRequest> downstream = new AtomicReference<>();
		FilterChain chain = (req, resp) -> downstream.set((HttpServletRequest) req);

		MockHttpServletResponse res = new MockHttpServletResponse();
		filter.doFilter(request("GET", "/api/orders/123", "Bearer " + valid), res, chain);

		assertThat(res.getStatus()).isEqualTo(200);
		assertThat(downstream.get().getHeader(JwtAuthenticationFilter.HEADER_USER_ID))
				.isEqualTo("01JRX8Z0M0P8QF3W9K2T7Y6C4B");
		assertThat(downstream.get().getHeader(JwtAuthenticationFilter.HEADER_USER_ROLES))
				.isEqualTo("BUYER,SELLER");
		assertThat(downstream.get().getHeader(JwtAuthenticationFilter.HEADER_USER_EMAIL))
				.isEqualTo("an@example.com");
		assertThat(downstream.get().getHeader(JwtAuthenticationFilter.HEADER_USER_USERNAME))
				.isEqualTo("an.nguyen");
	}
}