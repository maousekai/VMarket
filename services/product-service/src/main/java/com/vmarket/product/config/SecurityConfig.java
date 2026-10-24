package com.vmarket.product.config;

import java.util.ArrayList;
import java.util.List;

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
import org.springframework.security.web.header.writers.CacheControlHeadersWriter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import com.vmarket.product.security.JwtAuthenticationFilter;
import com.vmarket.product.security.SecurityErrorWriter;

import tools.jackson.databind.ObjectMapper;

/**
 * Spring Security cho product-service (PBL6-49): stateless, không CSRF (không có
 * cookie phiên), danh tính từ access token qua {@link JwtAuthenticationFilter}.
 *
 * <p>Hai tầng kiểm tra:
 * <ul>
 *   <li><b>Chuỗi filter</b> — chỉ các đường dẫn đọc liệt kê trong
 *       {@link #PUBLIC_READ_PATHS} (GET/HEAD) là công khai; mọi request khác phải có
 *       token hợp lệ, trả 401 trước khi binding/validation chạy. Ngoại lệ: API nội bộ
 *       xác thực bằng {@code X-Internal-Api-Key} ({@link #INTERNAL_PATHS}).</li>
 *   <li><b>{@link com.vmarket.product.security.RequestIdentity}</b> trong controller —
 *       kiểm tra vai trò SELLER/ADMIN (403) và lấy userId từ token.</li>
 * </ul>
 *
 * <p>Giới hạn đã biết: token chỉ được verify chữ ký + hạn (stateless), nên đăng
 * xuất / khoá tài khoản ở auth-service chỉ có hiệu lực ở đây khi access token hết hạn.
 */
@Configuration
@EnableConfigurationProperties(ProductJwtProperties.class)
public class SecurityConfig {

	/**
	 * Đường dẫn đọc công khai (GET/HEAD). Liệt kê cụ thể thay vì mở {@code GET /**}
	 * để endpoint GET thêm sau này mặc định phải đăng nhập. {@code /api/products/*}
	 * gồm chi tiết sản phẩm, {@code /health}, {@code /categories}, {@code /brands}.
	 * {@code /actuator/prometheus} mở cho Prometheus scrape (không gửi token).
	 */
	private static final String[] PUBLIC_READ_PATHS = {
			"/api/products",
			"/api/products/*",
			"/actuator/health",
			"/actuator/health/**",
			"/actuator/info",
			"/actuator/prometheus",
			"/v3/api-docs",
			"/v3/api-docs/**",
			"/swagger-ui.html",
			"/swagger-ui/**",
	};

	/** API nội bộ service-to-service: tự kiểm tra khoá bằng {@code InternalApiKeyGuard}. */
	private static final String[] INTERNAL_PATHS = {
			"/api/products/inventory/reservations",
			"/api/products/inventory/reservations/**",
			"/api/products/internal/**",
	};

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter,
			ObjectMapper objectMapper) throws Exception {
		RequestMatcher publicReads = publicReads();
		http
				.cors(Customizer.withDefaults())
				.csrf(csrf -> csrf.disable())
				.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.httpBasic(basic -> basic.disable())
				.formLogin(form -> form.disable())
				.logout(logout -> logout.disable())
				// Dữ liệu đọc công khai được cache như trước khi có Spring Security; mọi
				// response còn lại (có xác thực, lỗi 401/403) vẫn giữ no-store mặc định.
				.headers(headers -> headers
						.cacheControl(cache -> cache.disable())
						.addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
								new NegatedRequestMatcher(publicReads), new CacheControlHeadersWriter())))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(publicReads).permitAll()
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

	private static RequestMatcher publicReads() {
		PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
		List<RequestMatcher> matchers = new ArrayList<>();
		for (String pattern : PUBLIC_READ_PATHS) {
			matchers.add(paths.matcher(HttpMethod.GET, pattern));
			matchers.add(paths.matcher(HttpMethod.HEAD, pattern));
		}
		return new OrRequestMatcher(matchers);
	}

	/**
	 * URL bị firewall của Spring Security từ chối ({@code ;}, {@code %2F}, {@code //}...)
	 * trả envelope lỗi chuẩn thay vì body mặc định của Spring Boot. Việc từ chối xảy ra
	 * trước chuỗi security nên response này không có header CORS.
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
