package com.vmarket.notification.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.vmarket.notification.security.JwtAuthenticationFilter;
import com.vmarket.notification.security.SecurityErrorWriter;

import tools.jackson.databind.ObjectMapper;

/**
 * Stateless, không CSRF; danh tính lấy từ access token. Chỉ health / actuator công
 * khai, mọi API trung tâm thông báo đều cần đăng nhập và chỉ thấy dữ liệu của chính
 * người dùng (lọc theo {@code sub} ở tầng service).
 */
@Configuration
@EnableConfigurationProperties({ JwtProperties.class, NotificationProperties.class })
public class SecurityConfig {

	private static final String[] PUBLIC_PATHS = {
			"/api/notifications/health",
			"/actuator/health",
			"/actuator/health/**",
			"/actuator/info",
			"/actuator/prometheus",
	};

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter,
			ObjectMapper objectMapper) throws Exception {
		http
				.cors(Customizer.withDefaults())
				.csrf(csrf -> csrf.disable())
				.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.httpBasic(basic -> basic.disable())
				.formLogin(form -> form.disable())
				.logout(logout -> logout.disable())
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(HttpMethod.GET, PUBLIC_PATHS).permitAll()
						.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
						.requestMatchers("/error").permitAll()
						.anyRequest().authenticated())
				.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
				.exceptionHandling(ex -> ex
						.authenticationEntryPoint((request, response, e) -> SecurityErrorWriter.write(request, response,
								objectMapper, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
								"Bạn cần đăng nhập để thực hiện thao tác này"))
						.accessDeniedHandler((request, response, e) -> SecurityErrorWriter.write(request, response,
								objectMapper, HttpStatus.FORBIDDEN, "FORBIDDEN",
								"Bạn không có quyền thực hiện thao tác này")));
		return http.build();
	}

	/** Filter là {@code @Component}: tắt bản Boot tự đăng ký để chỉ chạy trong chuỗi security. */
	@Bean
	FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterServletRegistration(JwtAuthenticationFilter filter) {
		FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}

	/** Không có đăng nhập bằng mật khẩu ở service này — chặn Boot tự tạo user mặc định. */
	@Bean
	UserDetailsService userDetailsService() {
		return new InMemoryUserDetailsManager();
	}
}
