package com.vmarket.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Cấu hình Spring Security nền tảng cho auth-service.
 *
 * <p>PBL6-41 chỉ dựng khung: API stateless (không session), tắt CSRF, dùng lại
 * {@code CorsFilter} từ {@link CorsConfig}, và cho phép truy cập các endpoint
 * hạ tầng + toàn bộ {@code /api/auth/**} (các endpoint auth tự bảo vệ ở tầng
 * nghiệp vụ). Việc siết quyền theo vai trò (RBAC filter, kiểm tra JWT) thuộc
 * phạm vi PBL6-46.
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
	SecurityFilterChain securityFilterChain(HttpSecurity http, CorsConfigurationSource corsConfigurationSource)
			throws Exception {
		http
				.cors(cors -> cors.configurationSource(corsConfigurationSource))
				.csrf(csrf -> csrf.disable())
				.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.httpBasic(basic -> basic.disable())
				.formLogin(form -> form.disable())
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(PUBLIC_PATHS).permitAll()
						.anyRequest().authenticated());
		return http.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}
