package com.vmarket.gateway.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.vmarket.gateway.config.RateLimitProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Rate limit cơ bản (fixed-window theo IP) chạy trước tầng xác thực để che luôn
 * các endpoint public (vd đăng nhập) khỏi bị brute-force.
 *
 * <p>Khoá đếm là địa chỉ client THỰC: {@code getRemoteAddr()} — KHÔNG dùng
 * {@code X-Forwarded-For} vì gateway là điểm biên (network edge), header này do
 * client tự đặt được, kẻ tấn công chỉ cần xoay XFF là né per-IP limit. Khi tương
 * lai đặt reverse-proxy đáng tin trước gateway mới bật tin cậy XFF (kèm cơ chế
 * trusted-proxy). Preflight OPTIONS được bỏ qua để không làm hỏng CORS (do
 * {@code CorsConfig} xử lý).
 *
 * <p><b>Miễn trừ rate limit (L-2):</b> {@code /actuator/**} và {@code /error} được bỏ
 * qua để không làm gián đoạn health check và giám sát hệ thống.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
	private static final java.util.List<String> EXEMPT_PATHS = java.util.List.of("/actuator/**", "/error");

	private final InMemoryRateLimiter limiter;
	private final org.springframework.util.AntPathMatcher pathMatcher = new org.springframework.util.AntPathMatcher();

	public RateLimitFilter(RateLimitProperties properties) {
		this.limiter = properties.isEnabled()
				? new InMemoryRateLimiter(properties.getCapacity(), properties.getWindowSeconds())
				: null;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		if (limiter == null || CorsUtils.isPreFlightRequest(request) || isExempt(request)) {
			filterChain.doFilter(request, response);
			return;
		}

		String clientIp = resolveClientIp(request);
		if (!limiter.tryAcquire(clientIp)) {
			log.warn("Rate limit vượt ngưỡng cho client {}", clientIp);
			writeError(response);
			return;
		}
		filterChain.doFilter(request, response);
	}

	private boolean isExempt(HttpServletRequest request) {
		String path = request.getRequestURI();
		for (String pattern : EXEMPT_PATHS) {
			if (pathMatcher.match(pattern, path)) {
				return true;
			}
		}
		return false;
	}

	private String resolveClientIp(HttpServletRequest request) {
		return request.getRemoteAddr();
	}

	private void writeError(HttpServletResponse response) throws IOException {
		response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write(
				"{\"error\":{\"code\":\"RATE_LIMITED\",\"message\":\"Quá nhiều yêu cầu, vui lòng thử lại sau\"}}");
	}
}