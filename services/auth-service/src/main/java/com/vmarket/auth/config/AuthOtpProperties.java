package com.vmarket.auth.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

/**
 * Cấu hình OTP xác thực email — khoá {@code auth.otp.*}.
 */
@ConfigurationProperties(prefix = "auth.otp")
@Validated
@Getter
@Setter
public class AuthOtpProperties {

	/** Hiệu lực của mã OTP. */
	@NotNull
	private Duration ttl = Duration.ofMinutes(5);

	/** Số lần nhập sai tối đa cho một mã trước khi vô hiệu. */
	@Positive
	private int maxAttempts = 5;

	/** Không cho gửi lại mã mới trong khoảng này kể từ lần gửi gần nhất. */
	@NotNull
	private Duration resendCooldown = Duration.ofSeconds(60);

	/** Số mã tối đa được phát cho một email trong 1 giờ. */
	@Positive
	private int hourlyLimit = 5;
}
