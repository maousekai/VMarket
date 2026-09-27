package com.vmarket.auth.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.vmarket.auth.config.InternalApiProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Xác thực lời gọi service-to-service vào {@code /internal/**} bằng header
 * {@value InternalApiProperties#HEADER}.
 *
 * <p>Khoá đúng → đặt danh tính {@code ROLE_INTERNAL_SERVICE} vào SecurityContext.
 * Khoá thiếu/sai → không làm gì, để {@code SecurityConfig} trả 401 — filter không
 * tự viết response nên chỉ có một chỗ quyết định hình dạng lỗi.
 *
 * <p>So sánh bằng {@link MessageDigest#isEqual} (thời gian hằng) để không dò được
 * khoá từng ký tự qua thời gian phản hồi. Không log giá trị header (NFR-SEC-06).
 *
 * <p>Cố ý <b>không</b> là {@code @Component}: Spring Boot tự đăng ký mọi bean Filter
 * cho toàn bộ request, filter sẽ chạy thêm một lần ngoài security chain.
 *
 * <p><b>Phạm vi lấy từ chính {@code RequestMatcher} mà {@code SecurityConfig} dùng để
 * phân quyền</b>, không tự so chuỗi. Tự so bằng {@code getRequestURI()} thì hai bên
 * đang đọc hai thứ khác nhau: {@code getRequestURI()} gồm cả context path, còn matcher
 * so trên đường dẫn trong ứng dụng. Trùng khớp chỉ vì hiện tại context path rỗng —
 * thêm {@code server.servlet.context-path} là filter bỏ qua sạch các request hợp lệ và
 * mọi lời gọi nội bộ trả 401. Truyền cùng một đối tượng matcher thì không thể lệch.
 */
public class InternalApiKeyFilter extends OncePerRequestFilter {

	public static final String ROLE = "INTERNAL_SERVICE";

	private final byte[] expectedKey;
	private final RequestMatcher internalPaths;

	public InternalApiKeyFilter(InternalApiProperties properties, RequestMatcher internalPaths) {
		this.expectedKey = properties.getApiKey().getBytes(StandardCharsets.UTF_8);
		this.internalPaths = internalPaths;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !internalPaths.matches(request);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String provided = request.getHeader(InternalApiProperties.HEADER);
		if (provided != null && MessageDigest.isEqual(expectedKey, provided.getBytes(StandardCharsets.UTF_8))) {
			var authentication = new UsernamePasswordAuthenticationToken("internal-service", null,
					List.of(new SimpleGrantedAuthority("ROLE_" + ROLE)));
			SecurityContextHolder.getContext().setAuthentication(authentication);
		}
		chain.doFilter(request, response);
	}
}
