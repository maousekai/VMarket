package com.vmarket.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/** Cấu hình riêng của notification-service — khoá {@code app.notification.*}. */
@Validated
@Getter
@Setter
@ConfigurationProperties(prefix = "app.notification")
public class NotificationProperties {

	/** Khoá gọi API nội bộ của order-service và auth-service. */
	@NotBlank
	private String internalApiKey;

	@NotBlank
	private String orderUrl = "http://localhost:8086";

	@NotBlank
	private String authUrl = "http://localhost:8081";

	/** Gốc URL frontend để dựng link trong email. */
	@NotBlank
	private String frontendUrl = "http://localhost:5173";

	private final Email email = new Email();

	private final Push push = new Push();

	@Getter
	@Setter
	public static class Email {
		/** {@code log} | {@code smtp} | {@code brevo}. */
		private String provider = "log";
		private String from = "no-reply@vmarket.local";
		private String fromName = "VMarket";
		private final Brevo brevo = new Brevo();
	}

	@Getter
	@Setter
	public static class Brevo {
		private String apiKey;
	}

	@Getter
	@Setter
	public static class Push {
		/** {@code log} | {@code fcm}. */
		private String provider = "log";
		private final Fcm fcm = new Fcm();
	}

	@Getter
	@Setter
	public static class Fcm {
		private String projectId;
		/** Đường dẫn file JSON service account của Firebase. */
		private String credentialsFile;
	}
}
