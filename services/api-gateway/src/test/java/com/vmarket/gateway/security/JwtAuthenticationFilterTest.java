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
		security.setPublicPaths(List.of(
				"/api/auth/login",
				"/api/auth/register",
				"/api/auth/refresh",
				"/api/auth/logout",
				"/api/auth/sessions",
				"/api/auth/sessions/**",
				"/api/auth/health",
				"/actuator/**"));
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
				.claim("email_verified", true)
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
		assertThat(downstream.get().getHeader(JwtAuthenticationFilter.HEADER_USER_EMAIL_VERIFIED))
				.isEqualTo("true");
	}

	@Test
	void publicPath_stripsClientSuppliedXUserHeaders() throws Exception {
		MockHttpServletResponse res = new MockHttpServletResponse();
		AtomicReference<HttpServletRequest> downstream = new AtomicReference<>();
		FilterChain chain = (req, resp) -> downstream.set((HttpServletRequest) req);

		// Client gửi request public nhưng cố gắng spoof X-User-Id
		MockHttpServletRequest req = request("POST", "/api/auth/login", null);
		req.addHeader(JwtAuthenticationFilter.HEADER_USER_ID, "spoofed-user-id");
		req.addHeader(JwtAuthenticationFilter.HEADER_USER_ROLES, "ADMIN");

		filter.doFilter(req, new MockHttpServletResponse(), chain);

		assertThat(res.getStatus()).isEqualTo(200);
		// Header spoofed phải bị strip hoàn toàn (identity map rỗng)
		assertThat(downstream.get().getHeader(JwtAuthenticationFilter.HEADER_USER_ID)).isNull();
		assertThat(downstream.get().getHeader(JwtAuthenticationFilter.HEADER_USER_ROLES)).isNull();
	}

	@Test
	void protectedPath_validToken_overridesSpoofedHeaders() throws Exception {
		String valid = token(Instant.now(), Instant.now().plusSeconds(60));

		AtomicReference<HttpServletRequest> downstream = new AtomicReference<>();
		MockHttpServletResponse res = new MockHttpServletResponse();
		FilterChain chain = (req, resp) -> downstream.set((HttpServletRequest) req);

		MockHttpServletRequest req = request("GET", "/api/orders/123", "Bearer " + valid);
		// Client cố gắng spoof header khi có token hợp lệ
		req.addHeader(JwtAuthenticationFilter.HEADER_USER_ID, "spoofed-user-id");
		req.addHeader(JwtAuthenticationFilter.HEADER_USER_ROLES, "ADMIN");

		filter.doFilter(req, res, chain);

		assertThat(res.getStatus()).isEqualTo(200);
		// Header phải là giá trị từ JWT, không phải spoof
		assertThat(downstream.get().getHeader(JwtAuthenticationFilter.HEADER_USER_ID))
				.isEqualTo("01JRX8Z0M0P8QF3W9K2T7Y6C4B");
		assertThat(downstream.get().getHeader(JwtAuthenticationFilter.HEADER_USER_ROLES))
				.isEqualTo("BUYER,SELLER");
	}

	@Test
	void authProtectedEndpoint_withoutToken_rejected() throws Exception {
		MockHttpServletResponse res = new MockHttpServletResponse();
		filter.doFilter(request("GET", "/api/auth/me", null), res, new MockFilterChain());
		assertThat(res.getStatus()).isEqualTo(401);
	}

	private JwtAuthenticationFilter filterWithRoleRule(String pattern, List<String> methods, List<String> roles) {
		GatewayJwtProperties jwtProps = new GatewayJwtProperties();
		jwtProps.setSecret(SECRET);

		GatewaySecurityProperties.RoleRule rule = new GatewaySecurityProperties.RoleRule();
		rule.setPattern(pattern);
		rule.setMethods(methods);
		rule.setRoles(roles);

		GatewaySecurityProperties security = new GatewaySecurityProperties();
		security.setPublicPaths(List.of("/api/auth/login"));
		security.setRoleRequiredPaths(List.of(rule));

		return new JwtAuthenticationFilter(new JwtService(jwtProps), security);
	}

	@Test
	void roleRequiredPath_missingRole_rejected403() throws Exception {
		JwtAuthenticationFilter roleFilter = filterWithRoleRule("/api/shops/*/approve", List.of("POST"), List.of("ADMIN"));
		String buyerToken = token(Instant.now(), Instant.now().plusSeconds(60)); // roles = BUYER, SELLER

		MockHttpServletResponse res = new MockHttpServletResponse();
		roleFilter.doFilter(request("POST", "/api/shops/01J/approve", "Bearer " + buyerToken), res, new MockFilterChain());

		assertThat(res.getStatus()).isEqualTo(403);
		assertThat(res.getContentAsString()).contains("FORBIDDEN_ROLE");
	}

	@Test
	void roleRequiredPath_withRequiredRole_passes() throws Exception {
		JwtAuthenticationFilter roleFilter = filterWithRoleRule("/api/shops/*/approve", List.of("POST"), List.of("SELLER"));
		String token = token(Instant.now(), Instant.now().plusSeconds(60)); // roles = BUYER, SELLER

		MockHttpServletResponse res = new MockHttpServletResponse();
		roleFilter.doFilter(request("POST", "/api/shops/01J/approve", "Bearer " + token), res, new MockFilterChain());

		assertThat(res.getStatus()).isEqualTo(200);
	}

	@Test
	void roleRequiredPath_methodNotListed_ruleNotApplied() throws Exception {
		JwtAuthenticationFilter roleFilter = filterWithRoleRule("/api/shops/*/approve", List.of("POST"), List.of("ADMIN"));
		String buyerToken = token(Instant.now(), Instant.now().plusSeconds(60)); // roles = BUYER, SELLER

		MockHttpServletResponse res = new MockHttpServletResponse();
		// GET không nằm trong methods của rule -> chỉ cần token hợp lệ, không cần ADMIN.
		roleFilter.doFilter(request("GET", "/api/shops/01J/approve", "Bearer " + buyerToken), res, new MockFilterChain());

		assertThat(res.getStatus()).isEqualTo(200);
	}

	@Test
	void multipleMatchingRules_mustSatisfyAll_notJustFirst() throws Exception {
		// Rule rong (moi method /api/shops/**, can SELLER) khai bao TRUOC rule hep
		// hon, khat khe hon (chi POST .../approve, can ADMIN) - ca hai deu phai
		// thoa man, khong duoc dung o rule dau tien khop.
		GatewayJwtProperties jwtProps = new GatewayJwtProperties();
		jwtProps.setSecret(SECRET);

		GatewaySecurityProperties.RoleRule broad = new GatewaySecurityProperties.RoleRule();
		broad.setPattern("/api/shops/**");
		broad.setRoles(List.of("SELLER"));

		GatewaySecurityProperties.RoleRule narrow = new GatewaySecurityProperties.RoleRule();
		narrow.setPattern("/api/shops/*/approve");
		narrow.setMethods(List.of("POST"));
		narrow.setRoles(List.of("ADMIN"));

		GatewaySecurityProperties security = new GatewaySecurityProperties();
		security.setPublicPaths(List.of("/api/auth/login"));
		security.setRoleRequiredPaths(List.of(broad, narrow));

		JwtAuthenticationFilter roleFilter = new JwtAuthenticationFilter(new JwtService(jwtProps), security);
		String sellerToken = token(Instant.now(), Instant.now().plusSeconds(60)); // roles = BUYER, SELLER

		MockHttpServletResponse res = new MockHttpServletResponse();
		roleFilter.doFilter(request("POST", "/api/shops/01J/approve", "Bearer " + sellerToken), res, new MockFilterChain());

		// Co SELLER (thoa rule rong) nhung KHONG co ADMIN (khong thoa rule hep) -> van phai 403.
		assertThat(res.getStatus()).isEqualTo(403);
	}

	@Test
	void nonMatchingPath_roleRuleIgnored_onlyNeedsValidToken() throws Exception {
		JwtAuthenticationFilter roleFilter = filterWithRoleRule("/api/shops/*/approve", List.of("POST"), List.of("ADMIN"));
		String buyerToken = token(Instant.now(), Instant.now().plusSeconds(60)); // roles = BUYER, SELLER

		MockHttpServletResponse res = new MockHttpServletResponse();
		roleFilter.doFilter(request("GET", "/api/orders/123", "Bearer " + buyerToken), res, new MockFilterChain());

		assertThat(res.getStatus()).isEqualTo(200);
	}

	@Test
	void sessionManagementEndpoints_withoutBearerToken_pass() throws Exception {
		// PBL6-46: dinh danh qua cookie refresh_token, khong qua Authorization Bearer.
		MockHttpServletResponse logoutRes = new MockHttpServletResponse();
		filter.doFilter(request("POST", "/api/auth/logout", null), logoutRes, new MockFilterChain());
		assertThat(logoutRes.getStatus()).isEqualTo(200);

		MockHttpServletResponse listRes = new MockHttpServletResponse();
		filter.doFilter(request("GET", "/api/auth/sessions", null), listRes, new MockFilterChain());
		assertThat(listRes.getStatus()).isEqualTo(200);

		MockHttpServletResponse revokeRes = new MockHttpServletResponse();
		filter.doFilter(request("DELETE", "/api/auth/sessions/01JRX8Z0M0P8QF3W9K2T7Y6C4B", null), revokeRes,
				new MockFilterChain());
		assertThat(revokeRes.getStatus()).isEqualTo(200);
	}
}