package com.vmarket.notification.security;

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

import com.vmarket.notification.config.JwtProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Verify {@code Authorization: Bearer ...} (HS256, khoá chung với auth-service) và đặt
 * danh tính vào {@code SecurityContext}: principal = {@code sub} (userId). Không tin
 * header {@code X-User-Id} do client gửi.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
	private static final String BEARER_PREFIX = "Bearer ";
	private static final String ISSUER = "auth-service";

	private final SecretKey key;

	public JwtAuthenticationFilter(JwtProperties properties) {
		this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String header = request.getHeader("Authorization");
		if (header != null && header.startsWith(BEARER_PREFIX)
				&& SecurityContextHolder.getContext().getAuthentication() == null) {
			authenticate(header.substring(BEARER_PREFIX.length()).trim());
		}
		chain.doFilter(request, response);
	}

	private void authenticate(String token) {
		if (token.isEmpty()) {
			return;
		}
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
				return;
			}
			SecurityContextHolder.getContext().setAuthentication(
					new UsernamePasswordAuthenticationToken(userId, null, authorities(claims)));
		} catch (JwtException | IllegalArgumentException ex) {
			// Không log nội dung token.
			log.debug("Access token không hợp lệ: {}", ex.getClass().getSimpleName());
		}
	}

	private List<SimpleGrantedAuthority> authorities(Claims claims) {
		if (!(claims.get("roles") instanceof List<?> roles)) {
			return List.of();
		}
		return roles.stream()
				.filter(String.class::isInstance)
				.map(String.class::cast)
				.filter(r -> !r.isBlank())
				.map(r -> new SimpleGrantedAuthority("ROLE_" + r.trim().toUpperCase(Locale.ROOT)))
				.toList();
	}
}
