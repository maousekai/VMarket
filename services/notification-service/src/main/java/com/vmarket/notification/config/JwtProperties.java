package com.vmarket.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Khoá verify access token HS256 do auth-service ký — dùng chung prefix
 * {@code auth.jwt} và biến {@code AUTH_JWT_SECRET} với các service khác.
 */
@Validated
@Getter
@Setter
@ConfigurationProperties(prefix = "auth.jwt")
public class JwtProperties {

	@NotBlank
	@Size(min = 32, message = "AUTH_JWT_SECRET phải >= 32 ký tự (256-bit) cho HS256")
	private String secret;
}
