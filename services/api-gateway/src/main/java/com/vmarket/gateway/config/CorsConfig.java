package com.vmarket.gateway.config;

import java.io.IOException;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * CORS tai GATEWAY - chi xu ly PREFLIGHT (OPTIONS).
 *
 * Ly do: Spring MVC cua gateway tu tra loi OPTIONS (preflight) ma khong
 * forward den service -> browser se chan neu khong ai tra CORS header.
 * Ban WebMVC cua Spring Cloud Gateway cung khong ho tro property
 * "globalcors" nhu ban WebFlux, nen phai dung filter nay.
 *
 * QUAN TRONG: filter nay CHI tra loi preflight, khong them CORS header cho
 * request that (GET/POST...). CORS header cua response that do tung service
 * tu them (CorsConfig cua moi service Java / CORSMiddleware cua service
 * Python). Neu gateway cung them thi header se bi TRUNG gia tri
 * ("multiple values") va browser chan request.
 */
@Configuration
public class CorsConfig {

	@Bean
	public OncePerRequestFilter corsPreflightFilter(
			@Value("${app.cors.allowed-origins:http://localhost:5173,http://localhost:5174,http://localhost:3000}") List<String> allowedOrigins) {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(allowedOrigins);
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of("*"));
		config.setAllowCredentials(true);

		return new OncePerRequestFilter() {
			@Override
			protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
					throws ServletException, IOException {
				if (CorsUtils.isPreFlightRequest(request)) {
					new DefaultCorsProcessor().processRequest(config, request, response);
					return;
				}
				filterChain.doFilter(request, response);
			}
		};
	}
}

