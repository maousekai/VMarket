package com.vmarket.cart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * Loại {@link UserDetailsServiceAutoConfiguration}: cart-service xác thực bằng
 * JWT do auth-service ký (xem {@code SecurityConfig}) — không có form login hay
 * HTTP Basic, nên {@code InMemoryUserDetailsManager} mặc định của Spring Boot vô
 * dụng mà vẫn in cảnh báo {@code Using generated security password} mỗi lần khởi
 * động, khiến log khó đọc và dễ tưởng service có tài khoản mặc định.
 */
@SpringBootApplication(exclude = { UserDetailsServiceAutoConfiguration.class })
@ConfigurationPropertiesScan
public class CartServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(CartServiceApplication.class, args);
	}

}