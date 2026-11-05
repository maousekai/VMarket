package com.vmarket.notification.push;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.google.auth.oauth2.GoogleCredentials;
import com.vmarket.notification.config.NotificationProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Gửi push qua Firebase Cloud Messaging HTTP v1
 * ({@code POST https://fcm.googleapis.com/v1/projects/{projectId}/messages:send}).
 * Access token OAuth2 lấy từ service account (scope {@code firebase.messaging}),
 * thư viện tự làm mới khi hết hạn.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.notification.push.provider", havingValue = "fcm")
public class FcmPushSender implements PushSender {

	static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

	private final GoogleCredentials credentials;
	private final RestClient restClient;

	public FcmPushSender(NotificationProperties properties, RestClient.Builder builder) throws IOException {
		NotificationProperties.Fcm fcm = properties.getPush().getFcm();
		if (!StringUtils.hasText(fcm.getProjectId()) || !StringUtils.hasText(fcm.getCredentialsFile())) {
			throw new IllegalStateException(
					"app.notification.push.provider=fcm nhưng thiếu FCM_PROJECT_ID hoặc FCM_CREDENTIALS_FILE");
		}
		try (InputStream in = new FileInputStream(fcm.getCredentialsFile())) {
			this.credentials = GoogleCredentials.fromStream(in).createScoped(List.of(SCOPE));
		}
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(3));
		factory.setReadTimeout(Duration.ofSeconds(10));
		this.restClient = builder.clone()
				.requestFactory(factory)
				.baseUrl("https://fcm.googleapis.com/v1/projects/" + fcm.getProjectId() + "/messages:send")
				.build();
	}

	@Override
	public Result send(String deviceToken, String title, String body, Map<String, String> data) {
		Map<String, Object> message = Map.of("message", Map.of(
				"token", deviceToken,
				"notification", Map.of("title", title, "body", body),
				"data", data == null ? Map.of() : data));
		try {
			credentials.refreshIfExpired();
			restClient.post()
					.contentType(MediaType.APPLICATION_JSON)
					.header("Authorization", "Bearer " + credentials.getAccessToken().getTokenValue())
					.body(message)
					.retrieve()
					.toBodilessEntity();
			return Result.SENT;
		} catch (HttpClientErrorException ex) {
			// 404 UNREGISTERED / 400 INVALID_ARGUMENT: token không còn dùng được.
			if (ex.getStatusCode() == HttpStatus.NOT_FOUND
					|| (ex.getStatusCode() == HttpStatus.BAD_REQUEST
							&& ex.getResponseBodyAsString().contains("INVALID_ARGUMENT"))) {
				return Result.INVALID_TOKEN;
			}
			log.warn("FCM trả lỗi {}", ex.getStatusCode().value());
			return Result.FAILED;
		} catch (IOException | RestClientException ex) {
			log.warn("Không gửi được FCM: {}", ex.getClass().getSimpleName());
			return Result.FAILED;
		}
	}
}
