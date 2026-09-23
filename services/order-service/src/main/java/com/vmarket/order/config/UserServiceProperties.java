package com.vmarket.order.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Kết nối tới user-service ({@code app.user-service.*}).
 *
 * <p>Order-service chỉ đọc MỘT thứ: địa chỉ giao hàng ({@code GET
 * /api/users/me/addresses/{id}}) để chép snapshot vào đơn. Endpoint đó yêu cầu
 * Bearer token của chính người dùng nên {@link
 * com.vmarket.order.client.UserServiceClient} phải forward nguyên header
 * {@code Authorization} của request gốc.
 */
@ConfigurationProperties(prefix = "app.user-service")
@Validated
@Getter
@Setter
public class UserServiceProperties {

	/** Vd {@code http://user-service:8082} trong mạng Docker, {@code http://localhost:8082} khi chạy mvnw. */
	@NotBlank
	private String baseUrl;

	@NotNull
	private Duration connectTimeout = Duration.ofSeconds(2);

	@NotNull
	private Duration readTimeout = Duration.ofSeconds(5);
}