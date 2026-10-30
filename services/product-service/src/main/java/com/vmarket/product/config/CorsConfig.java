package com.vmarket.product.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Nguồn cấu hình CORS, được {@link SecurityConfig} dùng qua {@code http.cors(...)}
 * để CORS chạy ĐẦU chuỗi security — phản hồi 401 / 403 do Spring Security tự ghi
 * cũng mang header CORS, trình duyệt đọc được body lỗi. Riêng URL bị firewall từ
 * chối (400) bị chặn trước chuỗi security nên không có header CORS.
 *
 * <p>{@code allowedHeaders = *} bao gồm {@code Authorization} và {@code Idempotency-Key}
 * cho preflight của request ghi.
 */
@Configuration
public class CorsConfig {

	@Bean
	public CorsConfigurationSource corsConfigurationSource(
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
