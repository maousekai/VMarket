package com.vmarket.auth.email;

import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.vmarket.auth.config.AuthEmailProperties;
import com.vmarket.auth.exception.ApiException;

import lombok.extern.slf4j.Slf4j;

/**
 * Gửi email qua Brevo HTTP API ({@code POST https://api.brevo.com/v3/smtp/email},
 * header {@code api-key}). Kích hoạt khi {@code auth.email.provider=brevo}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "auth.email.provider", havingValue = "brevo")
public class BrevoEmailSender implements EmailSender {

	public static final String SEND_URL = "https://api.brevo.com/v3/smtp/email";

	private final RestClient restClient;
	private final Map<String, String> sender;

	public BrevoEmailSender(AuthEmailProperties props, RestClient.Builder restClientBuilder) {
		String apiKey = props.getBrevo().getApiKey();
		if (!StringUtils.hasText(apiKey)) {
			throw new IllegalStateException("auth.email.provider=brevo nhưng thiếu BREVO_API_KEY");
		}
		this.restClient = restClientBuilder
				.baseUrl(SEND_URL)
				.defaultHeader("api-key", apiKey)
				.defaultHeader("accept", MediaType.APPLICATION_JSON_VALUE)
				.build();
		this.sender = Map.of("name", props.getFromName(), "email", props.getFrom());
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
						log.error("Brevo trả lỗi {} khi gửi email tới {}", res.getStatusCode(), message.to());
						throw new ApiException("EMAIL_SEND_FAILED", HttpStatus.BAD_GATEWAY,
								"Không gửi được email, vui lòng thử lại sau");
					})
					.toBodilessEntity();
		} catch (ResourceAccessException ex) {
			log.error("Không kết nối được Brevo khi gửi email tới {}", message.to(), ex);
			throw new ApiException("EMAIL_SEND_FAILED", HttpStatus.BAD_GATEWAY,
					"Không gửi được email, vui lòng thử lại sau");
		}
	}
}
