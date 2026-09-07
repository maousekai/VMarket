package com.vmarket.auth.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Cấu hình JWT đọc từ biến môi trường (khoá {@code auth.jwt.*} trong
 * {@code application.yml} → {@code AUTH_JWT_SECRET}, {@code AUTH_JWT_ACCESS_TTL},
 * {@code AUTH_JWT_REFRESH_TTL}).
 *
 * <p>Service fail-fast khi thiếu secret ở môi trường prod. Secret <b>không</b>
 * được hardcode/commit; dev dùng giá trị mặc định rõ ràng là giả.
 */
@ConfigurationProperties(prefix = "auth.jwt")
@Validated
@Getter
@Setter
public class AuthJwtProperties {

	/** Khoá bí mật HS256 — tối thiểu 32 ký tự (256-bit). Chia sẻ với API Gateway để verify. */
	@NotBlank
	@Size(min = 32, message = "AUTH_JWT_SECRET phải >= 32 ký tự (256-bit) cho HS256")
	private String secret;

	/** Thời hạn access token (mặc định 15 phút, SRS NFR-SEC-02 ≤ 30 phút). */
	@NotNull
	private Duration accessTtl = Duration.ofMinutes(15);

	/** Thời hạn refresh token (mặc định 15 ngày). */
	@NotNull
	private Duration refreshTtl = Duration.ofDays(15);
}
