package com.vmarket.cart.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import javax.crypto.SecretKey;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.vmarket.cart.config.CartJwtProperties;
import com.vmarket.cart.config.InternalApiProperties;

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
 * Xác định danh tính người gọi, theo thứ tự ưu tiên:
 * <ol>
 *   <li><b>Access token</b> trong header {@code Authorization: Bearer ...} — verify
 *       chữ ký HS256 bằng khoá dùng chung với auth-service, kiểm {@code iss} và
 *       {@code exp}; principal là {@code sub} (userId). Đây là đường của mọi
 *       request từ browser (đi qua API Gateway).</li>
 *   <li><b>Cuộc gọi nội bộ</b> — chỉ trên {@code /internal/**}: {@code X-Internal-Api-Key}
 *       đúng khoá cấu hình thì tin {@code X-User-Id} đi kèm (đường của order-service
 *       khi đọc/xoá giỏ lúc đặt hàng). Trên route công khai {@code /api/cart/**} khoá
 *       bị bỏ qua (PBL6-50). Khoá nằm ở biến môi trường phía server, không có trong
 *       frontend.</li>
 * </ol>
 *
 * <p><b>Vì sao không tin thẳng header {@code X-User-Id}.</b> Gateway (PBL6-38) có
 * strip header {@code X-User-*} do client gửi, nhưng cổng 8085 publish ra host:
 * ai gọi thẳng service cũng tự đặt được header đó. Đọc giỏ của người khác chỉ cần
 * một chuỗi đoán được là lỗi P1 của review PR #23 — nay phải có token hợp lệ hoặc
 * khoá nội bộ.
 *
 * <p><b>Filter này không tự từ chối request.</b> Token thiếu/sai chỉ khiến
 * SecurityContext trống; việc trả 401 do {@code SecurityConfig} quyết định qua
 * {@code authorizeHttpRequests} — nhờ vậy endpoint công khai (health, swagger)
 * vẫn đi qua được mà không cần danh sách loại trừ trong đây.
 */
@Slf4j
@Component
public class CartAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";

	/** Khớp {@code iss} do auth-service ký và API Gateway kiểm tra (PBL6-38). */
	private static final String ISSUER = "auth-service";

	/** Claim vai trò: mảng chuỗi {@code ["BUYER","ADMIN"]} khớp {@code RoleName} của auth-service. */
	private static final String ROLES_CLAIM = "roles";

	/** Quyền của cuộc gọi nội bộ — {@code SecurityConfig} đòi quyền này cho {@code /internal/**}. */
	public static final String INTERNAL_AUTHORITY = "ROLE_INTERNAL";

	/** Tiền tố route nội bộ — nơi duy nhất chấp nhận khoá {@code X-Internal-Api-Key}. */
	public static final String INTERNAL_PREFIX = "/internal";

	/** CHUYỂN TIẾP (PBL6-50): route order-service bản cũ gọi. */
	private static final String LEGACY_CART_PATH = "/api/cart";
	private static final Pattern LEGACY_REMOVE_ITEM = Pattern.compile("/api/cart/items/[^/]+");

	private final SecretKey key;
	private final byte[] internalApiKey;
	private final boolean legacyCartPathsEnabled;

	public CartAuthenticationFilter(CartJwtProperties jwtProperties, InternalApiProperties internalApiProperties) {
		// Keys.hmacShaKeyFor bắt buộc khoá >= 32 byte cho HS256 và ném lỗi ngay lúc
		// khởi động nếu secret quá ngắn — fail sớm, đúng chỗ.
		this.key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
		this.internalApiKey = internalApiProperties.getApiKey().getBytes(StandardCharsets.UTF_8);
		this.legacyCartPathsEnabled = internalApiProperties.isLegacyCartPathsEnabled();
		if (legacyCartPathsEnabled) {
			log.warn("Chuyển tiếp PBL6-50 đang BẬT: khoá nội bộ còn được nhận trên GET/DELETE /api/cart. "
					+ "Đặt CART_LEGACY_INTERNAL_PATHS_ENABLED=false khi mọi order-service đã gọi /internal/cart");
		}
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {

		if (SecurityContextHolder.getContext().getAuthentication() == null) {
			String token = extractBearer(request);
			if (token != null) {
				authenticateWithToken(token);
			} else if (isInternalPath(request) || acceptsLegacyInternalCall(request)) {
				// Khoá nội bộ CHỈ có hiệu lực trên /internal/** (PBL6-50), cộng ba route
				// order-service bản cũ gọi trong giai đoạn chuyển tiếp.
				authenticateInternalCall(request);
			}
		}
		chain.doFilter(request, response);
	}

	private void authenticateWithToken(String token) {
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
			setAuthentication(userId, authorities(claims));

		} catch (JwtException | IllegalArgumentException ex) {
			// Hết hạn / sai chữ ký / sai định dạng đều rơi vào đây. KHÔNG log nội dung
			// token (NFR-SEC-06) và KHÔNG ném lỗi: để SecurityContext trống rồi tầng
			// phân quyền trả 401 với body chuẩn.
			log.debug("Access token không hợp lệ: {}", ex.getClass().getSimpleName());
		}
	}

	/**
	 * Cuộc gọi nội bộ service-to-service. Yêu cầu khoá {@code X-Internal-Api-Key}
	 * đúng; userId lấy từ {@code X-User-Id} của caller đã được xác thực.
	 *
	 * <p>So khoá bằng {@link MessageDigest#isEqual} (so sánh hằng thời gian) thay vì
	 * {@code String.equals}: {@code equals} dừng ở ký tự đầu tiên khác nhau, đủ để
	 * đoán dần khoá qua thời gian phản hồi.
	 */
	private void authenticateInternalCall(HttpServletRequest request) {
		String providedKey = request.getHeader(InternalApiProperties.HEADER);
		if (providedKey == null || !MessageDigest.isEqual(
				providedKey.getBytes(StandardCharsets.UTF_8), internalApiKey)) {
			return;
		}
		String userId = request.getHeader(InternalApiProperties.USER_ID_HEADER);
		if (userId == null || userId.isBlank()) {
			log.debug("Có khoá nội bộ nhưng thiếu {} — coi như chưa đăng nhập",
					InternalApiProperties.USER_ID_HEADER);
			return;
		}
		setAuthentication(userId, List.of(new SimpleGrantedAuthority(INTERNAL_AUTHORITY)));
	}

	/**
	 * Path nội bộ. Dùng {@code getServletPath()} (đã giải mã, chuẩn hoá, bỏ
	 * {@code ;param}) — cùng cách Spring Security khớp {@code /internal/**} — để hai
	 * nơi không hiểu khác nhau về một path lạ.
	 */
	static boolean isInternalPath(HttpServletRequest request) {
		String path = servletPath(request);
		return path.equals(INTERNAL_PREFIX) || path.startsWith(INTERNAL_PREFIX + "/");
	}

	/**
	 * CHUYỂN TIẾP (PBL6-50): cờ đang bật VÀ là một trong ba lời gọi của order-service bản
	 * cũ. Nơi DUY NHẤT quyết định — {@code SecurityConfig} gọi lại hàm này, để filter và
	 * tầng phân quyền không hiểu khác nhau. Xem {@link InternalApiProperties#isLegacyCartPathsEnabled()}.
	 */
	public boolean acceptsLegacyInternalCall(HttpServletRequest request) {
		return legacyCartPathsEnabled && isLegacyInternalCall(request);
	}

	static boolean isLegacyInternalCall(HttpServletRequest request) {
		String path = servletPath(request);
		String method = request.getMethod();
		if ("GET".equals(method)) {
			return path.equals(LEGACY_CART_PATH);
		}
		if ("DELETE".equals(method)) {
			return path.equals(LEGACY_CART_PATH) || LEGACY_REMOVE_ITEM.matcher(path).matches();
		}
		return false;
	}

	private static String servletPath(HttpServletRequest request) {
		String path = request.getServletPath();
		return request.getPathInfo() != null ? path + request.getPathInfo() : path;
	}

	private void setAuthentication(String userId, List<SimpleGrantedAuthority> authorities) {
		SecurityContextHolder.getContext()
				.setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, authorities));
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

	private String extractBearer(HttpServletRequest request) {
		String header = request.getHeader("Authorization");
		if (header == null || !header.startsWith(BEARER_PREFIX)) {
			return null;
		}
		String token = header.substring(BEARER_PREFIX.length()).trim();
		return token.isEmpty() ? null : token;
	}
}