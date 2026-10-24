package com.vmarket.product.config;

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
import org.springframework.security.web.firewall.RequestRejectedHandler;

import com.vmarket.product.security.JwtAuthenticationFilter;
import com.vmarket.product.security.RequestIdentity;
import com.vmarket.product.security.SecurityErrorWriter;

import tools.jackson.databind.ObjectMapper;

/**
 * Spring Security cho product-service (PBL6-49): stateless, không CSRF (không có
 * cookie phiên), danh tính từ access token qua {@link JwtAuthenticationFilter}.
 *
 * <p>Hai tầng kiểm tra:
 * <ul>
 *   <li><b>Chuỗi filter</b> — mọi request ghi (POST/PUT/PATCH/DELETE) phải có token
 *       hợp lệ, trả 401 trước khi binding/validation chạy. Ngoại lệ: API nội bộ
 *       xác thực bằng {@code X-Internal-Api-Key} (tồn kho, {@code /internal/**}).
 *       GET/HEAD công khai (xem danh mục, sản phẩm, health, swagger).</li>
 *   <li><b>{@link RequestIdentity}</b> trong controller — kiểm tra vai trò
 *       SELLER/ADMIN (403) và lấy userId từ token.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(ProductJwtProperties.class)
public class SecurityConfig {

	/** API nội bộ service-to-service: tự kiểm tra khoá bằng {@code InternalApiKeyGuard}. */
	private static final String[] INTERNAL_PATHS = {
			"/api/products/inventory/reservations",
			"/api/products/inventory/reservations/**",
			"/api/products/internal/**",
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
				// Danh mục / chi tiết sản phẩm là dữ liệu công khai, cho phép cache như
				// trước khi có Spring Security (mặc định của nó là no-store mọi response).
				.headers(headers -> headers.cacheControl(cache -> cache.disable()))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(HttpMethod.GET, "/**").permitAll()
						.requestMatchers(HttpMethod.HEAD, "/**").permitAll()
						.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
						.requestMatchers(INTERNAL_PATHS).permitAll()
						// Lỗi trong handler được forward tới /error; không mở thì body lỗi
						// thật bị thay bằng 401.
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

	/**
	 * URL bị firewall của Spring Security từ chối ({@code ;}, {@code %2F}, {@code //}...)
	 * trả envelope lỗi chuẩn thay vì body mặc định của Spring Boot.
	 */
	@Bean
	RequestRejectedHandler requestRejectedHandler(ObjectMapper objectMapper) {
		return (request, response, ex) -> SecurityErrorWriter.write(request, response, objectMapper,
				HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Đường dẫn yêu cầu không hợp lệ");
	}

	/**
	 * Filter là {@code @Component} nên Spring Boot sẽ tự đăng ký nó thêm một lần vào
	 * servlet container; tắt bản đó để filter chỉ chạy trong chuỗi security.
	 */
	@Bean
	FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterServletRegistration(JwtAuthenticationFilter filter) {
		FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}

	/**
	 * Không có đăng nhập bằng mật khẩu ở service này. Khai báo bean rỗng để Spring
	 * Boot không tự tạo user {@code user} với mật khẩu ngẫu nhiên và in nó ra log.
	 */
	@Bean
	UserDetailsService userDetailsService() {
		return new InMemoryUserDetailsManager();
	}
}
