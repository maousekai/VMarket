package com.vmarket.shop.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Nguồn cấu hình CORS cho shop-service khi bị gọi trực tiếp (cổng 8083) lúc debug.
 * Luồng chính đi qua API Gateway — gateway mới là nơi chịu trách nhiệm CORS.
 *
 * <p>Phải là bean {@link CorsConfigurationSource} chứ không phải {@code CorsFilter}
 * (bản skeleton cũ): {@link SecurityConfig} nạp nguồn này vào chuỗi filter của Spring
 * Security qua {@code http.cors(...)}. Chỉ khai {@code CorsFilter} thì Spring Security
 * không thấy rule nào từ {@code app.cors.allowed-origins}, và preflight
 * {@code OPTIONS /api/shops/me} bị đẩy xuống bước kiểm tra quyền rồi trả 401 — đúng
 * lỗi user-service từng gặp (xem {@code CorsPreflightTest} bên đó).
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
