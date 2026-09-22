package com.vmarket.shop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * Loại {@link UserDetailsServiceAutoConfiguration}: shop-service không có form login
 * hay HTTP Basic (danh tính lấy từ JWT do auth-service ký — xem {@code SecurityConfig}),
 * nên {@code InMemoryUserDetailsManager} mặc định vô dụng mà vẫn in cảnh báo
 * {@code Using generated security password} mỗi lần khởi động (giống user-service).
 */
@SpringBootApplication(exclude = { UserDetailsServiceAutoConfiguration.class })
@ConfigurationPropertiesScan
public class ShopServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(ShopServiceApplication.class, args);
	}

}
