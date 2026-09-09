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
 *   <li>Token hợp lệ → gắn identity (userId/roles/email/username) vào header
 *       request để truyền xuống service đích ({@code X-User-*}); KHÔNG phân quyền
 *       theo vai trò ở đây (thuộc PBL6-46).</li>
 * </ol>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

	public static final String HEADER_USER_ID = "X-User-Id";
	public static final String HEADER_USER_ROLES = "X-User-Roles";
	public static final String HEADER_USER_EMAIL = "X-User-Email";
	public static final String HEADER_USER_USERNAME = "X-User-Username";

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
			filterChain.doFilter(request, response);
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
			HttpServletRequest identityRequest = withIdentity(request, claims);
			filterChain.doFilter(identityRequest, response);
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

	private HttpServletRequest withIdentity(HttpServletRequest request, Claims claims) {
		String roles = "";
		Object rolesClaim = claims.get("roles");
		if (rolesClaim instanceof List<?> list) {
			roles = list.stream().map(String::valueOf).collect(Collectors.joining(","));
		}

		Map<String, String> extraHeaders = new LinkedHashMap<>();
		extraHeaders.put(HEADER_USER_ID, claims.getSubject());
		extraHeaders.put(HEADER_USER_ROLES, roles);
		String email = claims.get("email", String.class);
		if (email != null) {
			extraHeaders.put(HEADER_USER_EMAIL, email);
		}
		String username = claims.get("username", String.class);
		if (username != null) {
			extraHeaders.put(HEADER_USER_USERNAME, username);
		}
		return new IdentityRequestWrapper(request, extraHeaders);
	}

	private void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
		response.setStatus(status);
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write("{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}}");
	}

	/** Thêm các header identity vào request mà không thay đổi các header gốc. */
	private static class IdentityRequestWrapper extends HttpServletRequestWrapper {

		private final Map<String, String> extraHeaders;

		IdentityRequestWrapper(HttpServletRequest request, Map<String, String> extraHeaders) {
			super(request);
			this.extraHeaders = extraHeaders;
		}

		@Override
		public String getHeader(String name) {
			String value = extraHeaders.get(name);
			return value != null ? value : super.getHeader(name);
		}

		@Override
		public Enumeration<String> getHeaderNames() {
			Set<String> names = new LinkedHashSet<>(Collections.list(super.getHeaderNames()));
			names.addAll(extraHeaders.keySet());
			return Collections.enumeration(names);
		}

		@Override
		public Enumeration<String> getHeaders(String name) {
			String value = extraHeaders.get(name);
			return value != null ? Collections.enumeration(List.of(value)) : super.getHeaders(name);
		}
	}
}