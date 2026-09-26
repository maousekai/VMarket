package com.vmarket.cart.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import tools.jackson.databind.ObjectMapper;
import com.vmarket.cart.security.CartAuthenticationFilter;
import com.vmarket.cart.web.ErrorResponseWriter;

/**
 * Cấu hình Spring Security cho cart-service.
 *
 * <p>API stateless: không session, không CSRF (không có cookie phiên để bảo vệ),
 * danh tính lấy từ access token (hoặc khoá nội bộ cho cuộc gọi service-to-service)
 * qua {@link CartAuthenticationFilter}.
 *
 * <p>Mọi endpoint {@code /api/cart/**} đều bắt buộc có danh tính — giỏ hàng là dữ
 * liệu riêng của từng tài khoản, không có gì dành cho khách vãng lai. Chỉ health
 * endpoint là công khai vì nó không trả dữ liệu của ai cả.
 *
 * <p>Dùng phân quyền theo URL thay vì {@code @PreAuthorize}: bị từ chối ở tầng
 * filter thì đi thẳng qua {@code accessDeniedHandler} với body chuẩn, không phải
 * nhờ {@code GlobalExceptionHandler} bắt hộ.
 */
@Configuration
public class SecurityConfig {

	private static final String[] PUBLIC_PATHS = {
			// Health nghiệp vụ: frontend và monitoring gọi khi chưa có token.
			"/api/cart/health",
			// Spring Security lọc cả dispatch ERROR -> không mở /error thì lỗi trong
			// handler bị biến thành 403 rỗng thay vì trả đúng body lỗi.
			"/error",
			"/actuator/health",
			"/actuator/health/**",
			"/actuator/info",
			"/v3/api-docs/**",
			"/v3/api-docs.yaml",
			"/swagger-ui/**",
			"/swagger-ui.html",
	};

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http,
			CorsConfigurationSource corsConfigurationSource,
			CartAuthenticationFilter cartAuthenticationFilter,
			ObjectMapper objectMapper) throws Exception {
		http
				.cors(cors -> cors.configurationSource(corsConfigurationSource))
				.csrf(csrf -> csrf.disable())
				.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.httpBasic(basic -> basic.disable())
				.formLogin(form -> form.disable())
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(PUBLIC_PATHS).permitAll()
						.anyRequest().authenticated())
				// Đặt trước filter đăng nhập bằng form/username-password để danh tính
				// có mặt trong SecurityContext khi tầng phân quyền chạy.
				.addFilterBefore(cartAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
				.exceptionHandling(ex -> ex
						.authenticationEntryPoint(unauthorizedEntryPoint(objectMapper))
						.accessDeniedHandler(accessDeniedHandler(objectMapper)));
		return http.build();
	}

	/**
	 * 401 khi thiếu/sai danh tính. Mặc định Spring Security trả body rỗng; ở đây
	 * trả đúng hình dạng {@code { "error": { "code", "message" } }} như mọi lỗi
	 * khác để frontend chỉ cần một hàm đọc lỗi.
	 */
	private AuthenticationEntryPoint unauthorizedEntryPoint(ObjectMapper objectMapper) {
		return (request, response, ex) -> ErrorResponseWriter.write(response, objectMapper, HttpStatus.UNAUTHORIZED,
				"UNAUTHORIZED", "Cần đăng nhập để thao tác với giỏ hàng");
	}

	/** 403 khi đã có danh tính nhưng không đủ quyền (hiện chưa dùng — đặt sẵn cho RBAC sau này). */
	private AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
		return (request, response, ex) -> ErrorResponseWriter.write(response, objectMapper, HttpStatus.FORBIDDEN,
				"FORBIDDEN", "Bạn không có quyền thực hiện thao tác này");
	}
}