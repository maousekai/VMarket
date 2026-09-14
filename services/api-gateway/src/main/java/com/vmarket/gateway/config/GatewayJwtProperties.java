package com.vmarket.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Cấu hình JWT đọc từ biến môi trường (khoá {@code auth.jwt.*} trong
 * {@code application.yml} → {@code AUTH_JWT_SECRET}).
 *
 * <p>Secret <b>dùng chung với auth-service</b> (cùng biến {@code AUTH_JWT_SECRET})
 * để gateway tự xác minh token HS256 mà auth-service đã ký — gateway KHÔNG gọi
 * auth-service để verify (auth-service hiện không có endpoint introspect).
 *
 * <p>Service fail-fast khi thiếu secret ở môi trường prod: giá trị dev rõ ràng
 * nằm trong {@code application-dev.yml}, file base không có default.
 */
@ConfigurationProperties(prefix = "auth.jwt")
@Validated
public class GatewayJwtProperties {

	/** Khoá bí mật HS256 — tối thiểu 32 ký tự (256-bit), phải trùng với auth-service. */
	@NotBlank
	@Size(min = 32, message = "AUTH_JWT_SECRET phải >= 32 ký tự (256-bit) cho HS256")
	private String secret;

	public String getSecret() {
		return secret;
	}

	public void setSecret(String secret) {
		this.secret = secret;
	}
}