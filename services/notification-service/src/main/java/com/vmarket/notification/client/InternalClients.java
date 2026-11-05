package com.vmarket.notification.client;

import java.time.Duration;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.vmarket.notification.config.NotificationProperties;

/**
 * Gọi API nội bộ (khoá {@code X-Internal-Api-Key}) của order-service và auth-service.
 *
 * <p>404 → {@link Optional#empty()} (dữ liệu không tồn tại, thử lại cũng vô ích); lỗi
 * mạng / 5xx được ném ra để event bus retry rồi đưa vào DLQ.
 */
@Component
public class InternalClients {

	static final String KEY_HEADER = "X-Internal-Api-Key";

	private final RestClient order;
	private final RestClient auth;

	public InternalClients(NotificationProperties properties, RestClient.Builder builder) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(2));
		factory.setReadTimeout(Duration.ofSeconds(5));
		this.order = builder.clone()
				.requestFactory(factory)
				.baseUrl(properties.getOrderUrl())
				.defaultHeader(KEY_HEADER, properties.getInternalApiKey())
				.build();
		this.auth = builder.clone()
				.requestFactory(factory)
				.baseUrl(properties.getAuthUrl())
				.defaultHeader(KEY_HEADER, properties.getInternalApiKey())
				.build();
	}

	public Optional<OrderSnapshot> order(String orderId) {
		try {
			return Optional.ofNullable(order.get()
					.uri("/internal/delivery/orders/{id}", orderId)
					.retrieve()
					.body(OrderSnapshot.class));
		} catch (HttpClientErrorException ex) {
			if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
				return Optional.empty();
			}
			throw ex;
		}
	}

	public Optional<UserContact> contact(String userId) {
		try {
			return Optional.ofNullable(auth.get()
					.uri("/internal/users/{id}/contact", userId)
					.retrieve()
					.body(UserContact.class));
		} catch (HttpClientErrorException ex) {
			if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
				return Optional.empty();
			}
			throw ex;
		}
	}
}
