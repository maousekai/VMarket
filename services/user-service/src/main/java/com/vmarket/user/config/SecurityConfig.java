package com.vmarket.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import tools.jackson.databind.ObjectMapper;
import com.vmarket.user.security.JwtAuthenticationFilter;
import com.vmarket.user.service.IdempotencyService;
import com.vmarket.user.web.ErrorResponseWriter;
import com.vmarket.user.web.IdempotencyFilter;

/**
 * Cấu hình Spring Security cho user-service.
 *
 * <p>API stateless: không session, không CSRF (không có cookie phiên để bảo vệ),
 * danh tính lấy từ access token qua {@link JwtAuthenticationFilter}.
 *
 * <p>Khác auth-service ở một điểm quan trọng: auth-service mở {@code /api/auth/**}
 * cho tất cả (endpoint đăng ký/đăng nhập vốn phải công khai), còn ở đây <b>mọi</b>
 * endpoint {@code /api/users/**} đều bắt buộc đăng nhập — không có endpoint hồ sơ
 * nào dành cho khách vãng lai.
 */
@Configuration
public class SecurityConfig {

	private static final String[] PUBLIC_PATHS = {
			// Health nghiệp vụ: frontend và monitoring gọi để biết service còn sống,
			// cả hai đều chưa có token. Đây là endpoint DUY NHẤT dưới /api/users mở
			// công khai — nó không trả dữ liệu của ai cả.
			"/api/users/health",
			// Spring Security lọc cả dispatch ERROR -> nếu không mở /error thì lỗi
			// trong handler bị biến thành 403 rỗng thay vì trả đúng body lỗi.
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
			JwtAuthenticationFilter jwtAuthenticationFilter,
			IdempotencyService idempotencyService,
			IdempotencyProperties idempotencyProperties,
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
				// từ token có mặt trong SecurityContext khi tầng phân quyền chạy.
				.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
				// SAU AuthorizationFilter: Idempotency-Key chỉ có nghĩa trong phạm vi một
				// người dùng, nên phải chạy ở chỗ danh tính đã chắc chắn và request đã qua
				// được tầng phân quyền. Cố ý KHÔNG khai filter này là @Component — Spring
				// Boot sẽ tự đăng ký thêm nó một lần nữa vào chuỗi filter của servlet, chạy
				// trước cả Spring Security, tức là trước khi có danh tính.
				.addFilterAfter(new IdempotencyFilter(idempotencyService, objectMapper,
						idempotencyProperties.isEnabled()), AuthorizationFilter.class)
				.exceptionHandling(ex -> ex
						.authenticationEntryPoint(unauthorizedEntryPoint(objectMapper))
						.accessDeniedHandler(accessDeniedHandler(objectMapper)));
		return http.build();
	}

	/**
	 * 401 khi thiếu/sai token. Mặc định Spring Security trả body rỗng; ở đây trả
	 * đúng hình dạng {@code { "error": { "code", "message" } }} như mọi lỗi khác để
	 * frontend chỉ cần một hàm đọc lỗi.
	 */
	private AuthenticationEntryPoint unauthorizedEntryPoint(ObjectMapper objectMapper) {
		return (request, response, ex) -> ErrorResponseWriter.write(response, objectMapper, HttpStatus.UNAUTHORIZED,
				"UNAUTHORIZED", "Bạn cần đăng nhập để thực hiện thao tác này");
	}

	/** 403 khi đã đăng nhập nhưng không đủ quyền. */
	private AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
		return (request, response, ex) -> ErrorResponseWriter.write(response, objectMapper, HttpStatus.FORBIDDEN,
				"FORBIDDEN", "Bạn không có quyền thực hiện thao tác này");
	}
}
