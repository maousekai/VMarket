package com.vmarket.auth.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

/**
 * Cấu hình đặt lại mật khẩu (FR-AUTH-04) — khoá {@code auth.password-reset.*}.
 * Cùng giới hạn tần suất/số lần thử như {@link AuthOtpProperties} vì bài toán
 * keyspace/brute-force của mã 6 số là như nhau.
 */
@ConfigurationProperties(prefix = "auth.password-reset")
@Validated
@Getter
@Setter
public class AuthPasswordResetProperties {

	/** Hiệu lực của mã đặt lại mật khẩu. */
	@NotNull
	private Duration ttl = Duration.ofMinutes(5);

	/** Số lần nhập sai tối đa cho một mã trước khi vô hiệu. */
	@Positive
	private int maxAttempts = 5;

	/** Không cho gửi lại mã mới trong khoảng này kể từ lần gửi gần nhất. */
	@NotNull
	private Duration resendCooldown = Duration.ofSeconds(60);

	/** Số mã tối đa được phát cho một user trong 1 giờ. */
	@Positive
	private int hourlyLimit = 5;
}
