package com.vmarket.review.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Khoá verify JWT — dùng chung prefix {@code auth.jwt} / biến {@code AUTH_JWT_SECRET} với
 * auth-service. Không có mặc định ở profile prod: thiếu khoá là service dừng ngay lúc khởi động.
 */
@ConfigurationProperties(prefix = "auth.jwt")
@Validated
@Getter
@Setter
public class ReviewJwtProperties {

	@NotBlank
	@Size(min = 32, message = "AUTH_JWT_SECRET phải >= 32 ký tự (256-bit) cho HS256")
	private String secret;
}
