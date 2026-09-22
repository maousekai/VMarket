package com.vmarket.auth.security;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.vmarket.auth.dto.ErrorResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Chặn CSRF cho các endpoint đổi trạng thái phiên (PBL6-46, NFR-SEC-05) bằng cách xác
 * minh {@code Origin}/{@code Referer} của request — lớp phòng thủ THỨ HAI bên cạnh
 * {@code SameSite=Lax} của cookie {@code refresh_token} + CORS allowlist (xem javadoc
 * {@link com.vmarket.auth.config.SecurityConfig}, vốn đã là phòng thủ chính).
 *
 * <p>Phạm vi lấy từ {@code RequestMatcher} truyền vào (xem
 * {@code SecurityConfig#securityFilterChain} — cùng nguyên tắc "một matcher dùng
 * chung" như {@link InternalApiKeyFilter}), thường là các path đổi trạng thái phiên
 * ({@code /api/auth/logout}, {@code /api/auth/sessions/*}). {@code GET /api/auth/sessions}
 * (chỉ đọc) không cần chặn — CSRF không khai thác được qua request chỉ đọc, same-origin
 * policy của trình duyệt đã chặn kẻ tấn công đọc response.
 *
 * <p>Ưu tiên header {@code Origin}; trình duyệt không gửi (một số request same-origin
 * cũ, hoặc client không phải trình duyệt) thì dự phòng {@code Referer}. Nếu CẢ HAI đều
 * thiếu thì cho qua — không đủ thông tin để từ chối một cách an toàn, không nên chặn
 * nhầm client hợp lệ chỉ vì thiếu header optional.
 *
 * <p><b>Lưu ý:</b> {@code CorsFilter} của Spring (đăng ký qua {@code http.cors(...)} ở
 * {@code SecurityConfig}, chạy TRƯỚC filter này trong chain) đã tự chặn MỌI request có
 * header {@code Origin} không khớp allowlist, cho MỌI path — không riêng 3 endpoint mà
 * filter này canh. Nhánh kiểm {@code Origin} ở đây vì vậy thường không bao giờ chạy tới
 * trên thực tế (chỉ còn ý nghĩa phòng hờ nếu cấu hình CORS đổi khác đi sau này); giá trị
 * thật sự của filter này là nhánh dự phòng {@code Referer} — thứ {@code CorsFilter}
 * KHÔNG kiểm — và đảm bảo lỗi trả về đúng hình dạng chuẩn của dự án
 * ({@code { "error": { "code", "message" } }}) thay vì body dạng text của Spring.
 */
public class SessionOriginGuardFilter extends OncePerRequestFilter {

	private final List<String> allowedOrigins;
	private final RequestMatcher guardedPaths;
	private final ObjectMapper objectMapper;

	public SessionOriginGuardFilter(List<String> allowedOrigins, RequestMatcher guardedPaths,
			ObjectMapper objectMapper) {
		this.allowedOrigins = allowedOrigins;
		this.guardedPaths = guardedPaths;
		this.objectMapper = objectMapper;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !guardedPaths.matches(request);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String origin = request.getHeader("Origin");
		if (origin == null) {
			origin = originOf(request.getHeader("Referer"));
		}
		if (origin != null && !allowedOrigins.contains(origin)) {
			writeForbidden(response);
			return;
		}
		chain.doFilter(request, response);
	}

	/** Rút gọn {@code Referer} (có thể kèm path/query) về dạng origin {@code scheme://host[:port]}. */
	private static String originOf(String referer) {
		if (referer == null) {
			return null;
		}
		try {
			URI uri = new URI(referer);
			if (uri.getScheme() == null || uri.getHost() == null) {
				return null;
			}
			String origin = uri.getScheme() + "://" + uri.getHost();
			return uri.getPort() == -1 ? origin : origin + ":" + uri.getPort();
		} catch (URISyntaxException e) {
			return null;
		}
	}

	private void writeForbidden(HttpServletResponse response) throws IOException {
		response.setStatus(HttpStatus.FORBIDDEN.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		objectMapper.writeValue(response.getWriter(),
				ErrorResponse.of("ORIGIN_NOT_ALLOWED", "Origin của request không được phép gọi endpoint này"));
	}
}
