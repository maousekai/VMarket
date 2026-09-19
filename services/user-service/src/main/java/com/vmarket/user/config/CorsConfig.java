package com.vmarket.user.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Nguồn cấu hình CORS cho user-service khi bị gọi trực tiếp (cổng 8082) lúc debug.
 * Luồng chính đi qua API Gateway — gateway mới là nơi chịu trách nhiệm CORS.
 *
 * <p>Phải là bean {@link CorsConfigurationSource} (giống auth-service) chứ không
 * phải {@code CorsFilter}: {@link SecurityConfig} nạp nguồn này vào chuỗi filter
 * của Spring Security qua {@code http.cors(...)}. Nếu chỉ khai {@code CorsFilter},
 * Spring Security không thấy bean nào tên {@code corsConfigurationSource} và tự
 * tiêm {@code HandlerMappingIntrospector} mặc định — tức là không có rule nào từ
 * {@code app.cors.allowed-origins}, nên preflight {@code OPTIONS /api/users/me}
 * bị đẩy xuống bước kiểm tra quyền và trả 401.
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
