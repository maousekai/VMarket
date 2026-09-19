package com.vmarket.auth.config;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import com.vmarket.auth.dto.ErrorResponse;
import com.vmarket.auth.security.InternalApiKeyFilter;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Cấu hình Spring Security nền tảng cho auth-service.
 *
 * <p>PBL6-41 chỉ dựng khung: API stateless (không session), tắt CSRF, dùng lại
 * {@code CorsFilter} từ {@link CorsConfig}, và cho phép truy cập các endpoint
 * hạ tầng + toàn bộ {@code /api/auth/**} (các endpoint auth tự bảo vệ ở tầng
 * nghiệp vụ). Việc siết quyền theo vai trò (RBAC filter, kiểm tra JWT) thuộc
 * phạm vi PBL6-46.
 *
 * <p>PBL6-13: thêm API nội bộ {@code /internal/**} cho service khác gọi (user-service —
 * đổi mật khẩu, Admin quản lý tài khoản), xác thực bằng {@link InternalApiKeyFilter}.
 * Không có khoá hợp lệ → 401 với body lỗi chuẩn.
 */
@Configuration
public class SecurityConfig {

	private static final String[] PUBLIC_PATHS = {
			"/api/auth/**",
			// Spring Security lọc cả dispatch ERROR -> nếu không mở /error thì 404/500
			// trong handler permitAll bị biến thành 403 rỗng thay vì trả đúng lỗi.
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
	SecurityFilterChain securityFilterChain(HttpSecurity http, CorsConfigurationSource corsConfigurationSource,
			InternalApiProperties internalApiProperties, ObjectMapper objectMapper) throws Exception {
		http
				.cors(cors -> cors.configurationSource(corsConfigurationSource))
				.csrf(csrf -> csrf.disable())
				.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.httpBasic(basic -> basic.disable())
				.formLogin(form -> form.disable())
				.addFilterBefore(new InternalApiKeyFilter(internalApiProperties),
						UsernamePasswordAuthenticationFilter.class)
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/internal/**").hasRole(InternalApiKeyFilter.ROLE)
						.requestMatchers(PUBLIC_PATHS).permitAll()
						.anyRequest().authenticated())
				.exceptionHandling(ex -> ex
						.authenticationEntryPoint((request, response, e) -> writeError(response, objectMapper,
								HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Thiếu hoặc sai thông tin xác thực")));
		return http.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	private static void writeError(HttpServletResponse response, ObjectMapper objectMapper,
			HttpStatus status, String code, String message) throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		objectMapper.writeValue(response.getWriter(), ErrorResponse.of(code, message));
	}
}
