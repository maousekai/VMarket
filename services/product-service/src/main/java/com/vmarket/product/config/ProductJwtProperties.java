package com.vmarket.product.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Cấu hình JWT của product-service — chỉ có {@code secret} để <b>verify</b> token
 * do auth-service ký (HS256, khoá đối xứng nên hai bên phải dùng chung một chuỗi).
 *
 * <p>Dùng chung prefix {@code auth.jwt} và biến môi trường {@code AUTH_JWT_SECRET}
 * với auth-service và order-service. Không có giá trị mặc định ở bất kỳ profile nào
 * (kể cả {@code dev}): thiếu biến thì service dừng ngay lúc khởi động thay vì chạy
 * với khoá công khai. Chạy local thì nạp giá trị dev từ {@code .env.example}.
 */
@Validated
@ConfigurationProperties(prefix = "auth.jwt")
public class ProductJwtProperties {

	/**
	 * Khoá HS256 — tối thiểu 32 ký tự. Chặn ở bước bind cấu hình vì
	 * {@code Keys.hmacShaKeyFor} sẽ ném {@code WeakKeyException} với khoá ngắn hơn.
	 */
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
