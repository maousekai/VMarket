package com.vmarket.gateway.security;

import java.io.IOException;
import java.util.Collections;
import static com.vmarket.gateway.config.SearchContract.RATE_LIMITED_CODE;
import static com.vmarket.gateway.config.SearchContract.RATE_LIMITED_MESSAGE;

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
 * <p>Khoá đếm là IP client thật do {@link ClientIpResolver} xác định: mặc định
 * {@code getRemoteAddr()}; {@code X-Forwarded-For} chỉ được tin khi kết nối đến từ
 * proxy nằm trong {@code app.rate-limit.trusted-proxies} (vd nginx của frontend),
 * nên client gọi thẳng gateway không thể xoay XFF để né giới hạn. Bộ đếm là
 * {@link RateLimiter} (RAM hoặc Redis, xem {@code RateLimitConfig}). Preflight
 * OPTIONS được bỏ qua để không làm hỏng CORS (do {@code CorsConfig} xử lý).
 *
 * <p><b>Miễn trừ rate limit (L-2):</b> {@code /actuator/**} và {@code /error} được bỏ
 * qua để không làm gián đoạn health check và giám sát hệ thống.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
	private static final java.util.List<String> EXEMPT_PATHS = java.util.List.of("/actuator/**", "/error");

	private static final String XFF_HEADER = "X-Forwarded-For";

	private final RateLimiter limiter;
	private final ClientIpResolver clientIpResolver;
	private final org.springframework.util.AntPathMatcher pathMatcher = new org.springframework.util.AntPathMatcher();

	public RateLimitFilter(RateLimitProperties properties, RateLimiter limiter, ClientIpResolver clientIpResolver) {
		this.limiter = properties.isEnabled() ? limiter : null;
		this.clientIpResolver = clientIpResolver;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		if (limiter == null || CorsUtils.isPreFlightRequest(request) || isExempt(request)) {
			filterChain.doFilter(request, response);
			return;
		}

		String clientIp = ClientIpResolver.bucketKey(resolveClientIp(request));
		if (!limiter.tryAcquire(clientIp)) {
			log.warn("Rate limit vượt ngưỡng cho client {}", clientIp);
			writeError(request, response);
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
		return clientIpResolver.resolve(request.getRemoteAddr(), Collections.list(request.getHeaders(XFF_HEADER)));
	}

	private void writeError(HttpServletRequest request, HttpServletResponse response) throws IOException {
		ErrorResponseWriter.write(response, HttpStatus.TOO_MANY_REQUESTS.value(),
				RATE_LIMITED_CODE, RATE_LIMITED_MESSAGE, request.getRequestURI());
	}
}
