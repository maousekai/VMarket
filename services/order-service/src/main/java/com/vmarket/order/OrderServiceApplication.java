package com.vmarket.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * Loại {@link UserDetailsServiceAutoConfiguration}: order-service không có form login hay HTTP
 * Basic (danh tính lấy từ JWT do auth-service ký — xem {@code SecurityConfig}), nên {@code
 * InMemoryUserDetailsManager} mặc định của Spring Boot vô dụng ở đây mà vẫn in cảnh báo {@code
 * Using generated security password} mỗi lần khởi động, khiến log khó đọc và dễ tưởng service có
 * tài khoản mặc định.
 */
@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
@org.springframework.scheduling.annotation.EnableScheduling
@ConfigurationPropertiesScan
public class OrderServiceApplication {
    @Bean
    Clock orderClock() {
        return Clock.systemUTC();
    }

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
