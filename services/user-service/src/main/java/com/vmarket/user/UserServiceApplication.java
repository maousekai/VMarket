package com.vmarket.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Loại {@link UserDetailsServiceAutoConfiguration}: user-service không có form
 * login hay HTTP Basic (danh tính lấy từ JWT do auth-service ký — xem
 * {@code SecurityConfig}), nên {@code InMemoryUserDetailsManager} mặc định của
 * Spring Boot vô dụng ở đây mà vẫn in cảnh báo {@code Using generated security
 * password} mỗi lần khởi động, khiến log khó đọc và dễ tưởng service có tài khoản
 * mặc định.
 *
 * <p>{@code @EnableScheduling} cho đúng một việc: dọn bảng {@code idempotency_keys}
 * theo chu kỳ ({@code IdempotencyService.purgeExpired}). Bảng đó ghi thêm một dòng
 * cho mỗi request có {@code Idempotency-Key} nhưng chỉ hữu ích trong vài phút, nên
 * không dọn thì nó phình vô hạn.
 */
@SpringBootApplication(exclude = { UserDetailsServiceAutoConfiguration.class })
@ConfigurationPropertiesScan
@EnableScheduling
public class UserServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(UserServiceApplication.class, args);
	}

}
