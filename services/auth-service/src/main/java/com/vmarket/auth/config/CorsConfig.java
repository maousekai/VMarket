package com.vmarket.auth.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Nguồn cấu hình CORS cho auth-service khi bị gọi trực tiếp (cổng 8081) lúc debug.
 * Luồng chính đi qua API Gateway — gateway mới là nơi chịu trách nhiệm CORS.
 *
 * <p>Được {@link SecurityConfig} nạp vào chuỗi filter của Spring Security (qua
 * {@code http.cors(...)}) nên preflight {@code OPTIONS} được xử lý trước khi tới
 * bước kiểm tra quyền.
 */
@Configuration
public class CorsConfig {

	@Bean
	CorsConfigurationSource corsConfigurationSource(
			@Value("${app.cors.allowed-origins:http://localhost:5173,http://localhost:5174,http://localhost:3000}") List<String> allowedOrigins) {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(allowedOrigins);
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of("*"));
		config.setAllowCredentials(true);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);
		return source;
	}
}
