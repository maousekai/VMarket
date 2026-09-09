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
 * <p>Khoá đếm là client IP: ưu tiên {@code X-Forwarded-For} (phần tử đầu tiên),
 * fallback {@code getRemoteAddr()}. Preflight OPTIONS được bỏ qua để không làm hỏng
 * CORS (do {@code CorsConfig} xử lý).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

	private final InMemoryRateLimiter limiter;

	public RateLimitFilter(RateLimitProperties properties) {
		this.limiter = properties.isEnabled()
				? new InMemoryRateLimiter(properties.getCapacity(), properties.getWindowSeconds())
				: null;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		if (limiter == null || CorsUtils.isPreFlightRequest(request)) {
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

	private String resolveClientIp(HttpServletRequest request) {
		String forwardedFor = request.getHeader("X-Forwarded-For");
		if (forwardedFor != null && !forwardedFor.isBlank()) {
			int comma = forwardedFor.indexOf(',');
			return comma > 0 ? forwardedFor.substring(0, comma).trim() : forwardedFor.trim();
		}
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