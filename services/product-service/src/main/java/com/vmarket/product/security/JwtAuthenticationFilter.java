package com.vmarket.product.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.vmarket.product.config.ProductJwtProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Verify access token {@code Authorization: Bearer ...} (HS256, khoá dùng chung
 * với auth-service) rồi đặt danh tính vào {@code SecurityContext}: principal là
 * {@code sub} (userId), authority là claim {@code roles} kèm tiền tố {@code ROLE_}.
 *
 * <p>PBL6-49: trước đây product-service tin thẳng header {@code X-User-Id} /
 * {@code X-User-Roles}; cổng 8084 publish ra host nên ai gọi thẳng vào service
 * (không qua gateway) cũng tự xưng được ADMIN. Giờ danh tính chỉ đến từ token
 * đã verify, giống order-service.
 *
 * <p>Filter không tự từ chối request: token thiếu/sai chỉ khiến SecurityContext
 * trống. Danh mục, chi tiết sản phẩm và endpoint nội bộ (khoá
 * {@code X-Internal-Api-Key}) vẫn không cần token; {@link RequestIdentity} mới
 * là nơi trả 401/403 cho thao tác của seller/admin.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
	private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

	private static final String BEARER_PREFIX = "Bearer ";

	/** Khớp {@code iss} do auth-service ký và API Gateway kiểm tra. */
	private static final String ISSUER = "auth-service";

	private static final String ROLES_CLAIM = "roles";

	private final SecretKey key;

	public JwtAuthenticationFilter(ProductJwtProperties properties) {
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
			Claims claims = Jwts.parser()
					.verifyWith(key)
					.requireIssuer(ISSUER)
					.clockSkewSeconds(30)
					.build()
					.parseSignedClaims(token)
					.getPayload();

			String userId = claims.getSubject();
			if (userId == null || userId.isBlank()) {
				log.debug("Token hợp lệ nhưng thiếu claim 'sub' — coi như chưa đăng nhập");
				return;
			}
			var authentication = new UsernamePasswordAuthenticationToken(userId, null, authorities(claims));
			SecurityContextHolder.getContext().setAuthentication(authentication);
		} catch (JwtException | IllegalArgumentException ex) {
			// Không log nội dung token; để SecurityContext trống, RequestIdentity trả 401.
			log.debug("Access token không hợp lệ: {}", ex.getClass().getSimpleName());
		}
	}

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
