package com.vmarket.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Cấu hình gửi email — khoá {@code auth.email.*}, đọc từ biến môi trường
 * ({@code AUTH_EMAIL_PROVIDER}, {@code AUTH_EMAIL_FROM}, {@code BREVO_API_KEY}...).
 */
@ConfigurationProperties(prefix = "auth.email")
@Validated
@Getter
@Setter
public class AuthEmailProperties {

	/** {@code brevo} = gọi Brevo HTTP API; {@code log} = chỉ ghi log (dev/test). */
	private String provider = "log";

	/** Địa chỉ hiển thị người gửi. */
	@NotBlank
	@Email
	private String from = "no-reply@vmarket.local";

	/** Tên hiển thị người gửi. */
	private String fromName = "VMarket";

	private final Brevo brevo = new Brevo();

	@Getter
	@Setter
	public static class Brevo {
		/** API key Brevo — chỉ bắt buộc khi {@code provider = brevo}. */
		private String apiKey;
	}
}
