package com.vmarket.shop.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import javax.crypto.SecretKey;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.vmarket.shop.config.ShopJwtProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Đọc access token từ header {@code Authorization: Bearer ...}, verify chữ ký HS256
 * bằng khoá dùng chung với auth-service, rồi đặt danh tính vào {@code SecurityContext}.
 * Cùng quy tắc với {@code com.vmarket.user.security.JwtAuthenticationFilter}.
 *
 * <p><b>Principal là {@code userId}</b> (claim {@code sub}). Controller lấy bằng
 * {@code @AuthenticationPrincipal String userId} — không endpoint nào nhận
 * {@code ownerId} từ body hay path, nên người bán A không thể thao tác gian hàng của
 * B bằng cách sửa tham số (NFR-SEC-03, IDOR).
 *
 * <p><b>Không tin header {@code X-User-*} của gateway.</b> Cổng 8083 publish ra host
 * nên request có thể không đi qua gateway; tự verify token mới chắc chắn. Gateway
 * vẫn chuyển nguyên header {@code Authorization} xuống kể cả với route public
 * ({@code GET /api/shops/**}), nên {@code GET /api/shops/me} có token vẫn nhận ra
 * người gọi.
 *
 * <p><b>Filter này không tự từ chối request.</b> Token thiếu/sai chỉ khiến
 * SecurityContext trống; việc trả 401/403 do {@code SecurityConfig} quyết định.
 */
@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";

	/** Khớp {@code iss} do auth-service ký và API Gateway kiểm tra (PBL6-38). */
	private static final String ISSUER = "auth-service";

	/** Claim vai trò: mảng chuỗi {@code ["BUYER", "ADMIN"]} khớp {@code RoleName} của auth-service. */
	private static final String ROLES_CLAIM = "roles";

	private final SecretKey key;

	public JwtAuthenticationFilter(ShopJwtProperties properties) {
		// Keys.hmacShaKeyFor ném lỗi ngay lúc khởi động nếu secret < 32 byte.
		this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {

		String token = extractToken(request);
		if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
			authenticate(token);
		}
		chain.doFilter(request, response);
	}

	private void authenticate(String token) {
		try {
			// Cùng quy tắc với gateway: đúng chữ ký, đúng issuer, chưa hết hạn (lệch
			// đồng hồ tối đa 30 giây).
			Claims claims = Jwts.parser()
					.verifyWith(key)
					.requireIssuer(ISSUER)
					.clockSkewSeconds(30)
					.build()
					.parseSignedClaims(token)
					.getPayload();

			String userId = claims.getSubject();
			if (userId == null || userId.isBlank()) {
				log.debug("Token hợp lệ nhưng thiếu claim 'sub' — bỏ qua, coi như chưa đăng nhập");
				return;
			}

			var authentication = new UsernamePasswordAuthenticationToken(userId, null, authorities(claims));
			SecurityContextHolder.getContext().setAuthentication(authentication);

		} catch (JwtException | IllegalArgumentException ex) {
			// KHÔNG log nội dung token (NFR-SEC-06) và KHÔNG ném lỗi: để SecurityContext
			// trống rồi tầng phân quyền trả 401 với body chuẩn.
			log.debug("Access token không hợp lệ: {}", ex.getClass().getSimpleName());
		}
	}

	/**
	 * Chuyển claim {@code roles} thành authority, thêm tiền tố {@code ROLE_} vì
	 * {@code hasRole("ADMIN")} của Spring ngầm tìm authority {@code ROLE_ADMIN}.
	 */
	private List<SimpleGrantedAuthority> authorities(Claims claims) {
		Object raw = claims.get(ROLES_CLAIM);
		if (!(raw instanceof List<?> roles)) {
			return List.of();
		}
		return roles.stream()
				.filter(String.class::isInstance)
				.map(String.class::cast)
				.filter(r -> !r.isBlank())
				.map(r -> new SimpleGrantedAuthority("ROLE_" + r.trim().toUpperCase(Locale.ROOT)))
				.toList();
	}

	private String extractToken(HttpServletRequest request) {
		String header = request.getHeader("Authorization");
		if (header == null || !header.startsWith(BEARER_PREFIX)) {
			return null;
		}
		String token = header.substring(BEARER_PREFIX.length()).trim();
		return token.isEmpty() ? null : token;
	}
}
