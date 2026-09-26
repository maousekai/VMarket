package com.vmarket.shop.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import tools.jackson.databind.ObjectMapper;
import com.vmarket.shop.security.JwtAuthenticationFilter;
import com.vmarket.shop.web.ErrorResponseWriter;

/**
 * Cấu hình Spring Security cho shop-service.
 *
 * <p>API stateless: không session, không CSRF, danh tính lấy từ access token qua
 * {@link JwtAuthenticationFilter}.
 *
 * <p><b>Phân quyền theo đường dẫn, tập trung ở một chỗ</b> (ma trận RBAC — SRS 7.2):
 * <table>
 *   <tr><th>Đường dẫn</th><th>Ai được gọi</th></tr>
 *   <tr><td>{@code /api/shops/admin/**}</td><td>ADMIN — duyệt / từ chối / đình chỉ (FR-SHOP-04)</td></tr>
 *   <tr><td>{@code /api/shops/me/**}</td><td>Đã đăng nhập — chủ gian hàng (FR-SHOP-02)</td></tr>
 *   <tr><td>{@code POST /api/shops}</td><td>BUYER — đăng ký mở gian hàng (FR-SHOP-01)</td></tr>
 *   <tr><td>{@code GET /api/shops/{id}}</td><td>Mọi người, kể cả khách (FR-SHOP-03)</td></tr>
 * </table>
 *
 * <p><b>Thứ tự các rule là có chủ ý</b> — Spring Security dùng rule khớp ĐẦU TIÊN, mà
 * {@code GET /api/shops/*} (trang công khai) cũng khớp {@code /api/shops/me} và
 * {@code /api/shops/admin}. Hai rule đó phải đứng trước, nếu không trang "gian hàng
 * của tôi" và danh sách Admin sẽ mở cho khách vãng lai.
 *
 * <p><b>{@code /me/**} cố ý không đòi vai trò SELLER.</b> Vai trò SELLER do
 * auth-service cấp khi nhận {@code ShopApproved} (SRS §8.1) và chỉ xuất hiện trong
 * token ở lần làm mới kế tiếp; người vừa nộp hồ sơ (còn "Chờ duyệt"/"Bị từ chối")
 * chưa bao giờ có nó mà vẫn phải xem và sửa được hồ sơ của mình. Quyền ở đây là
 * <b>quyền sở hữu</b>: mọi thao tác tra theo {@code owner_id = sub} nên không chạm
 * được gian hàng của người khác (NFR-SEC-03).
 *
 * <p>Dùng phân quyền theo URL thay vì {@code @PreAuthorize}: bị từ chối ở tầng filter
 * thì đi thẳng qua {@code accessDeniedHandler} với body chuẩn, không phải nhờ
 * {@code GlobalExceptionHandler} bắt hộ.
 */
@Configuration
public class SecurityConfig {

	private static final String[] PUBLIC_PATHS = {
			// Health nghiệp vụ: frontend và monitoring gọi khi chưa có token.
			"/api/shops/health",
			// Spring Security lọc cả dispatch ERROR -> không mở /error thì lỗi trong
			// handler bị biến thành 403 rỗng thay vì trả đúng body lỗi.
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
	SecurityFilterChain securityFilterChain(HttpSecurity http,
			CorsConfigurationSource corsConfigurationSource,
			JwtAuthenticationFilter jwtAuthenticationFilter,
			ObjectMapper objectMapper) throws Exception {
		http
				.cors(cors -> cors.configurationSource(corsConfigurationSource))
				.csrf(csrf -> csrf.disable())
				.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.httpBasic(basic -> basic.disable())
				.formLogin(form -> form.disable())
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(PUBLIC_PATHS).permitAll()
						.requestMatchers("/api/shops/admin", "/api/shops/admin/**").hasRole("ADMIN")
						.requestMatchers("/api/shops/me", "/api/shops/me/**").authenticated()
						.requestMatchers(HttpMethod.POST, "/api/shops").hasRole("BUYER")
						// Trang gian hàng công khai — PHẢI đứng sau /me và /admin (xem javadoc).
						.requestMatchers(HttpMethod.GET, "/api/shops/*").permitAll()
						.anyRequest().authenticated())
				.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
				.exceptionHandling(ex -> ex
						.authenticationEntryPoint(unauthorizedEntryPoint(objectMapper))
						.accessDeniedHandler(accessDeniedHandler(objectMapper)));
		return http.build();
	}

	/** 401 khi thiếu/sai token — cùng hình dạng body lỗi với mọi lỗi khác. */
	private AuthenticationEntryPoint unauthorizedEntryPoint(ObjectMapper objectMapper) {
		return (request, response, ex) -> ErrorResponseWriter.write(response, objectMapper, HttpStatus.UNAUTHORIZED,
				"UNAUTHORIZED", "Bạn cần đăng nhập để thực hiện thao tác này");
	}

	/** 403 khi đã đăng nhập nhưng không đủ vai trò (vd không phải ADMIN). */
	private AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
		return (request, response, ex) -> ErrorResponseWriter.write(response, objectMapper, HttpStatus.FORBIDDEN,
				"FORBIDDEN", "Bạn không có quyền thực hiện thao tác này");
	}
}
