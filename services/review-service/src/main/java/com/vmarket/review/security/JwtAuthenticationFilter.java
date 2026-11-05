package com.vmarket.review.security;

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

import com.vmarket.review.config.ReviewJwtProperties;

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
 * Verify access token HS256 do auth-service ký rồi đặt {@code userId} (claim {@code sub}) làm
 * principal. Controller lấy bằng {@code @AuthenticationPrincipal String userId} — không endpoint nào
 * nhận userId từ body/path.
 *
 * <p>Filter không tự từ chối request: token thiếu/sai chỉ để SecurityContext trống, việc trả 401
 * do {@code SecurityConfig} quyết định. Service vẫn tự verify dù gateway đã làm (NFR-SEC-03) vì
 * cổng 8089 có thể bị gọi thẳng.
 */
@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";
	private static final String ISSUER = "auth-service";
	private static final String ROLES_CLAIM = "roles";

	private final SecretKey key;

	public JwtAuthenticationFilter(ReviewJwtProperties properties) {
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
			var signed = Jwts.parser()
					.verifyWith(key)
					.requireIssuer(ISSUER)
					.clockSkewSeconds(30)
					.build()
					.parseSignedClaims(token);
			Claims claims = signed.getPayload();
			if (claims.getExpiration() == null || !"HS256".equals(signed.getHeader().getAlgorithm())) {
				return;
			}
			String userId = claims.getSubject();
			if (userId == null || userId.isBlank()) {
				return;
			}
			var authentication = new UsernamePasswordAuthenticationToken(userId, null, authorities(claims));
			SecurityContextHolder.getContext().setAuthentication(authentication);
		} catch (JwtException | IllegalArgumentException ex) {
			// Không log nội dung token (NFR-SEC-06).
			log.debug("Access token không hợp lệ: {}", ex.getClass().getSimpleName());
		}
	}

	/** Claim {@code roles: ["BUYER","SELLER"]} → authority {@code ROLE_*} cho {@code hasRole(...)}. */
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
