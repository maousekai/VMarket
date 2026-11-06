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
 * Gọi API nội bộ (khoá {@code X-Internal-Api-Key}) của order-service, auth-service và
 * shop-service.
 *
 * <p>404 → {@link Optional#empty()} (dữ liệu không tồn tại, thử lại cũng vô ích); lỗi
 * mạng / 5xx được ném ra để event bus retry rồi đưa vào DLQ.
 */
@Component
public class InternalClients {

	static final String KEY_HEADER = "X-Internal-Api-Key";

	private final RestClient order;
	private final RestClient auth;
	private final RestClient shop;

	public InternalClients(NotificationProperties properties, RestClient.Builder builder) {
		this.order = client(builder, properties.getOrderUrl(), properties.getInternalApiKey());
		this.auth = client(builder, properties.getAuthUrl(), properties.getInternalApiKey());
		this.shop = client(builder, properties.getShopUrl(), properties.getInternalApiKey());
	}

	/** Timeout ngắn để một service treo không giữ listener thread của event bus. */
	public static SimpleClientHttpRequestFactory timeouts(Duration connect, Duration read) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(connect);
		factory.setReadTimeout(read);
		return factory;
	}

	private static RestClient client(RestClient.Builder builder, String baseUrl, String key) {
		return builder.clone()
				.requestFactory(timeouts(Duration.ofSeconds(2), Duration.ofSeconds(5)))
				.baseUrl(baseUrl)
				.defaultHeader(KEY_HEADER, key)
				.build();
	}

	/** Snapshot đơn qua endpoint đọc KHÔNG khoá dòng của order-service. */
	public Optional<OrderSnapshot> order(String orderId) {
		return get(order, "/internal/notification/orders/{id}", orderId, OrderSnapshot.class);
	}

	public Optional<UserContact> contact(String userId) {
		return get(auth, "/internal/users/{id}/contact", userId, UserContact.class);
	}

	public Optional<ShopOwnerInfo> shopOwner(String shopId) {
		return get(shop, "/internal/shops/{id}/owner", shopId, ShopOwnerInfo.class);
	}

	private static <T> Optional<T> get(RestClient client, String uri, String id, Class<T> type) {
		try {
			return Optional.ofNullable(client.get().uri(uri, id).retrieve().body(type));
		} catch (HttpClientErrorException ex) {
			if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
				return Optional.empty();
			}
			throw ex;
		}
	}
}
