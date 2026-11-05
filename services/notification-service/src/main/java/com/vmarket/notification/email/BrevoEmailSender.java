package com.vmarket.notification.email;

import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.vmarket.notification.config.NotificationProperties;

/**
 * Gửi email qua Brevo HTTP API — cùng nhà cung cấp auth-service đang dùng cho OTP.
 * Kích hoạt khi {@code app.notification.email.provider=brevo}.
 */
@Component
@ConditionalOnProperty(name = "app.notification.email.provider", havingValue = "brevo")
public class BrevoEmailSender implements EmailSender {

	static final String SEND_URL = "https://api.brevo.com/v3/smtp/email";

	private final RestClient restClient;
	private final Map<String, String> sender;

	public BrevoEmailSender(NotificationProperties properties, RestClient.Builder builder) {
		String apiKey = properties.getEmail().getBrevo().getApiKey();
		if (!StringUtils.hasText(apiKey)) {
			throw new IllegalStateException("app.notification.email.provider=brevo nhưng thiếu BREVO_API_KEY");
		}
		this.restClient = builder.clone()
				.baseUrl(SEND_URL)
				.defaultHeader("api-key", apiKey)
				.defaultHeader("accept", MediaType.APPLICATION_JSON_VALUE)
				.build();
		this.sender = Map.of("name", properties.getEmail().getFromName(), "email", properties.getEmail().getFrom());
	}

	@Override
	public String provider() {
		return "brevo";
	}

	@Override
	public void send(EmailMessage message) {
		Map<String, Object> body = Map.of(
				"sender", sender,
				"to", List.of(Map.of("email", message.to())),
				"subject", message.subject(),
				"htmlContent", message.htmlBody(),
				"textContent", message.textBody());
		try {
			restClient.post()
					.contentType(MediaType.APPLICATION_JSON)
					.body(body)
					.retrieve()
					.onStatus(HttpStatusCode::isError, (req, res) -> {
						throw new EmailDeliveryException("Brevo trả lỗi " + res.getStatusCode().value());
					})
					.toBodilessEntity();
		} catch (RestClientException ex) {
			throw new EmailDeliveryException("Không kết nối được Brevo", ex);
		}
	}
}
