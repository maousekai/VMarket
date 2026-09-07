package com.vmarket.user.security;

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

import com.vmarket.user.config.UserJwtProperties;

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
 * Đọc access token từ header {@code Authorization: Bearer ...}, verify chữ ký
 * HS256 bằng khoá dùng chung với auth-service, rồi đặt danh tính vào
 * {@code SecurityContext}.
 *
 * <p><b>Principal là {@code userId}</b> (claim {@code sub}) — chính là
 * {@code users.id} bên Identity Service. Controller lấy bằng
 * {@code @AuthenticationPrincipal String userId}, nhờ đó không endpoint nào phải
 * nhận userId từ body hay path (người dùng sẽ sửa được thành id của người khác).
 *
 * <p><b>Filter này không tự từ chối request.</b> Token thiếu/sai chỉ khiến
 * SecurityContext trống; việc trả 401 do {@code SecurityConfig} quyết định qua
 * {@code authorizeHttpRequests}. Tách như vậy để endpoint công khai (health,
 * swagger) vẫn đi qua được filter mà không cần danh sách loại trừ trong đây.
 */
@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";

	/**
	 * Tên claim chứa vai trò.
	 *
	 * <p><b>Giả định cần đối chiếu khi PBL6-43 phát hành token thật:</b> auth-service
	 * chưa có endpoint đăng nhập nên hình dạng claim chưa được chốt. Ở đây quy ước
	 * claim {@code roles} là mảng chuỗi {@code ["BUYER", "ADMIN"]} — khớp với
	 * {@code RoleName} của auth-service. Nếu PBL6-43 chọn tên khác thì sửa đúng
	 * hằng số này, không phải sửa rải rác.
	 */
	private static final String ROLES_CLAIM = "roles";

	private final SecretKey key;

	public JwtAuthenticationFilter(UserJwtProperties properties) {
		// Keys.hmacShaKeyFor bắt buộc khoá >= 32 byte cho HS256 và sẽ ném lỗi ngay
		// lúc khởi động nếu secret quá ngắn — fail sớm, đúng chỗ, thay vì tới request
		// đầu tiên mới hỏng.
		this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {

		String token = extractToken(request);
		if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
			authenticate(token, request);
		}
		chain.doFilter(request, response);
	}

	private void authenticate(String token, HttpServletRequest request) {
		try {
			Claims claims = Jwts.parser()
					.verifyWith(key)
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
			// Token hết hạn / sai chữ ký / sai định dạng đều rơi vào đây. KHÔNG log
			// nội dung token (NFR-SEC-06) và KHÔNG ném lỗi: cứ để SecurityContext
			// trống rồi tầng phân quyền trả 401 với body chuẩn.
			log.debug("Access token không hợp lệ: {}", ex.getClass().getSimpleName());
		}
	}

	/**
	 * Chuyển claim {@code roles} thành authority của Spring Security.
	 *
	 * <p>Thêm tiền tố {@code ROLE_} vì {@code hasRole("ADMIN")} của Spring ngầm tìm
	 * authority {@code ROLE_ADMIN}. Token chứa {@code "ADMIN"} trần, nên nếu không
	 * thêm tiền tố ở đây thì mọi endpoint admin sẽ trả 403 dù token đúng vai trò.
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
