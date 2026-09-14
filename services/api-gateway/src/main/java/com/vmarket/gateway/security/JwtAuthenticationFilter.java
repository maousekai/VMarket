package com.vmarket.gateway.security;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.vmarket.gateway.config.GatewaySecurityProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Xác thực JWT tập trung tại gateway.
 *
 * <p>Luồng:
 * <ol>
 *   <li>Bỏ qua preflight OPTIONS (CORS do {@code CorsConfig} xử lý).</li>
 *   <li>Nếu route thuộc nhóm public (mọi method hoặc GET/HEAD) → đi thẳng.</li>
 *   <li>Ngược lại bắt buộc có {@code Authorization: Bearer <token>}; verify bằng
 *       {@link JwtService}, thiếu/sai/hết hạn → 401 JSON.</li>
 *   <li>Token hợp lệ → gắn identity (userId/roles/email/username/emailVerified)
 *       vào header request để truyền xuống service đích ({@code X-User-*}); KHÔNG
 *       phân quyền theo vai trò ở đây (thuộc PBL6-46).</li>
 * </ol>
 *
 * <p><b>Chống giả mạo identity (H-1):</b> các header {@code X-User-*} là do GATEWAY
 * sinh ra, client KHÔNG được tự cung cấp. Vì vậy filter LUÔN strip mọi header
 * {@code X-User-*} từ request gốc (kể cả route public/preflight) rồi mới ghi đè
 * bằng giá trị xác thực từ JWT — nếu có. Nhờ vậy service phía sau có thể tin header
 * {@code X-User-*} mà không lo bị client spoof.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

	public static final String HEADER_USER_ID = "X-User-Id";
	public static final String HEADER_USER_ROLES = "X-User-Roles";
	public static final String HEADER_USER_EMAIL = "X-User-Email";
	public static final String HEADER_USER_USERNAME = "X-User-Username";
	public static final String HEADER_USER_EMAIL_VERIFIED = "X-User-Email-Verified";

	/** Các header identity do gateway tự sinh — client KHÔNG được tự đặt. */
	private static final Set<String> TRUSTED_IDENTITY_HEADERS = Set.of(
			HEADER_USER_ID, HEADER_USER_ROLES, HEADER_USER_EMAIL, HEADER_USER_USERNAME, HEADER_USER_EMAIL_VERIFIED);

	private static final String BEARER_PREFIX = "Bearer ";

	private final JwtService jwtService;
	private final GatewaySecurityProperties securityProperties;
	private final AntPathMatcher pathMatcher = new AntPathMatcher();

	public JwtAuthenticationFilter(JwtService jwtService, GatewaySecurityProperties securityProperties) {
		this.jwtService = jwtService;
		this.securityProperties = securityProperties;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		if (CorsUtils.isPreFlightRequest(request) || isPublic(request)) {
			// Public/preflight: khong yeu cau token nhung VAN strip X-User-* do
			// client gui len (identity map rong) de khong bi gia mao.
			filterChain.doFilter(new IdentityRequestWrapper(request, Map.of()), response);
			return;
		}

		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.startsWith(BEARER_PREFIX)) {
			writeError(response, HttpStatus.UNAUTHORIZED.value(), "UNAUTHORIZED", "Thiếu hoặc sai định dạng token");
			return;
		}

		String token = header.substring(BEARER_PREFIX.length()).trim();
		try {
			Claims claims = jwtService.parse(token);
			filterChain.doFilter(new IdentityRequestWrapper(request, identityHeaders(claims)), response);
		} catch (JwtException | IllegalArgumentException ex) {
			log.warn("JWT không hợp lệ: {}", ex.getMessage());
			writeError(response, HttpStatus.UNAUTHORIZED.value(), "UNAUTHORIZED", "Token không hợp lệ hoặc đã hết hạn");
		}
	}

	private boolean isPublic(HttpServletRequest request) {
		String path = request.getRequestURI();
		for (String pattern : securityProperties.getPublicPaths()) {
			if (pathMatcher.match(pattern, path)) {
				return true;
			}
		}
		if (isSafeMethod(request.getMethod())) {
			for (String pattern : securityProperties.getPublicGetPaths()) {
				if (pathMatcher.match(pattern, path)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean isSafeMethod(String method) {
		return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
	}

	private Map<String, String> identityHeaders(Claims claims) {
		String roles = "";
		Object rolesClaim = claims.get("roles");
		if (rolesClaim instanceof List<?> list) {
			roles = list.stream().map(String::valueOf).collect(Collectors.joining(","));
		}

		Map<String, String> headers = new LinkedHashMap<>();
		headers.put(HEADER_USER_ID, claims.getSubject());
		headers.put(HEADER_USER_ROLES, roles);
		String email = claims.get("email", String.class);
		if (email != null) {
			headers.put(HEADER_USER_EMAIL, email);
		}
		String username = claims.get("username", String.class);
		if (username != null) {
			headers.put(HEADER_USER_USERNAME, username);
		}
		Boolean emailVerified = claims.get("email_verified", Boolean.class);
		if (emailVerified != null) {
			headers.put(HEADER_USER_EMAIL_VERIFIED, emailVerified.toString());
		}
		return headers;
	}

	private void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
		response.setStatus(status);
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write("{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}}");
	}

	/**
	 * Bọc request để: (1) loại bỏ mọi header {@code X-User-*} do client gửi lên,
	 * (2) ghi đè bằng giá trị identity đã xác thực (nếu có). Với request không xác
	 * thực thì {@code identity} rỗng → header {@code X-User-*} bị strip hoàn toàn.
	 */
	private static class IdentityRequestWrapper extends HttpServletRequestWrapper {

		private final Map<String, String> identity;

		IdentityRequestWrapper(HttpServletRequest request, Map<String, String> identity) {
			super(request);
			this.identity = identity;
		}

		@Override
		public String getHeader(String name) {
			if (TRUSTED_IDENTITY_HEADERS.contains(name)) {
				return identity.get(name);
			}
			return super.getHeader(name);
		}

		@Override
		public Enumeration<String> getHeaders(String name) {
			if (TRUSTED_IDENTITY_HEADERS.contains(name)) {
				String value = identity.get(name);
				return value != null ? Collections.enumeration(List.of(value)) : Collections.emptyEnumeration();
			}
			return super.getHeaders(name);
		}

		@Override
		public Enumeration<String> getHeaderNames() {
			Set<String> names = new LinkedHashSet<>(Collections.list(super.getHeaderNames()));
			names.removeAll(TRUSTED_IDENTITY_HEADERS);
			names.addAll(identity.keySet());
			return Collections.enumeration(names);
		}
	}
}