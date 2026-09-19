package com.vmarket.user.config;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import tools.jackson.databind.ObjectMapper;
import com.vmarket.user.dto.ErrorResponse;
import com.vmarket.user.security.JwtAuthenticationFilter;

import jakarta.servlet.http.HttpServletResponse;

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
@EnableMethodSecurity
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
		return (request, response, ex) -> writeError(response, objectMapper, HttpStatus.UNAUTHORIZED,
				"UNAUTHORIZED", "Bạn cần đăng nhập để thực hiện thao tác này");
	}

	/** 403 khi đã đăng nhập nhưng không đủ vai trò (ví dụ endpoint chỉ dành cho ADMIN). */
	private AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
		return (request, response, ex) -> writeError(response, objectMapper, HttpStatus.FORBIDDEN,
				"FORBIDDEN", "Bạn không có quyền thực hiện thao tác này");
	}

	private void writeError(HttpServletResponse response, ObjectMapper objectMapper,
			HttpStatus status, String code, String message) throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		objectMapper.writeValue(response.getWriter(), ErrorResponse.of(code, message));
	}
}
