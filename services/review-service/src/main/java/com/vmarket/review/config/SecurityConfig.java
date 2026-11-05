package com.vmarket.review.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import com.vmarket.review.security.JwtAuthenticationFilter;
import com.vmarket.review.web.ErrorResponseWriter;

import tools.jackson.databind.ObjectMapper;

/**
 * Phân quyền Review Service.
 *
 * <ul>
 * <li>Đọc đánh giá / điểm tổng hợp của sản phẩm, gian hàng: công khai.</li>
 * <li>Viết đánh giá, xem đánh giá của tôi: BUYER (FR-REV-01).</li>
 * <li>{@code /api/reviews/seller/**}: SELLER (phản hồi đánh giá — FR-REV-03).</li>
 * <li>{@code /api/reviews/admin/**}: ADMIN (kiểm duyệt — FR-REV-04).</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain security(HttpSecurity http, JwtAuthenticationFilter jwt,
			@Qualifier("corsConfigurationSource") CorsConfigurationSource cors, ObjectMapper json) throws Exception {
		return http.cors(c -> c.configurationSource(cors))
				.csrf(c -> c.disable())
				.sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.httpBasic(c -> c.disable())
				.formLogin(c -> c.disable())
				.authorizeHttpRequests(c -> c
						.requestMatchers("/error", "/actuator/health/**", "/actuator/info", "/actuator/prometheus",
								"/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
						.permitAll()
						.requestMatchers(HttpMethod.GET, "/api/reviews/health", "/api/reviews/products/**",
								"/api/reviews/shops/**")
						.permitAll()
						.requestMatchers("/api/reviews/admin/**").hasRole("ADMIN")
						.requestMatchers("/api/reviews/seller/**").hasRole("SELLER")
						.requestMatchers(HttpMethod.POST, "/api/reviews").hasRole("BUYER")
						.requestMatchers("/api/reviews/me", "/api/reviews/me/**").hasRole("BUYER")
						.anyRequest().authenticated())
				.addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class)
				.exceptionHandling(c -> c
						.authenticationEntryPoint((req, res, ex) -> ErrorResponseWriter.write(res, json,
								HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Bạn cần đăng nhập"))
						.accessDeniedHandler((req, res, ex) -> ErrorResponseWriter.write(res, json,
								HttpStatus.FORBIDDEN, "FORBIDDEN", "Bạn không có quyền")))
				.build();
	}
}
