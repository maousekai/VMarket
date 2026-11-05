package com.vmarket.review.client;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.vmarket.review.config.ReviewProperties;
import com.vmarket.review.exception.ApiException;

import tools.jackson.databind.JsonNode;

/**
 * Hỏi lại Order / Shop Service bằng chính access token của người gọi.
 *
 * <p>Chuyển tiếp token (thay vì API key nội bộ) để service nguồn tự áp quy tắc sở hữu của nó:
 * {@code GET /api/orders/{id}} chỉ trả đơn của đúng người mua, {@code GET /api/shops/me} chỉ trả
 * gian hàng của đúng seller. Review Service không phải tin bất kỳ id nào client gửi lên.
 */
@Component
public class ReviewClients {

	private final ReviewProperties config;
	private final RestClient http;

	public ReviewClients(ReviewProperties config, RestClient.Builder builder) {
		this.config = config;
		var factory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
		factory.setReadTimeout(Duration.ofSeconds(5));
		this.http = builder.requestFactory(factory).build();
	}

	/**
	 * Đơn của người mua đang đăng nhập. Order Service trả 404 cho cả đơn không tồn tại lẫn đơn của
	 * người khác — giữ nguyên để không lộ sự tồn tại của đơn.
	 */
	public JsonNode myOrder(String orderId, String authorization) {
		try {
			return call(http.get().uri(config.getOrderUrl() + "/api/orders/{id}", orderId)
					.header("Authorization", authorization));
		} catch (UpstreamRejected ex) {
			if (ex.status == HttpStatus.NOT_FOUND) {
				throw ApiException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng của bạn");
			}
			throw ApiException.forbidden("ORDER_ACCESS_DENIED", "Không xác minh được đơn hàng của bạn");
		}
	}

	/** Id gian hàng của seller đang đăng nhập. */
	public String myShopId(String authorization) {
		try {
			String id = call(http.get().uri(config.getShopUrl() + "/api/shops/me")
					.header("Authorization", authorization)).path("id").asString();
			if (id.isBlank()) {
				throw ApiException.unavailable("DEPENDENCY_UNAVAILABLE", "Shop Service trả dữ liệu không hợp lệ");
			}
			return id;
		} catch (UpstreamRejected ex) {
			throw ApiException.forbidden("SHOP_NOT_FOUND", "Bạn chưa có gian hàng");
		}
	}

	private JsonNode call(RestClient.RequestHeadersSpec<?> request) {
		try {
			JsonNode body = request.retrieve().body(JsonNode.class);
			if (body == null) {
				throw ApiException.unavailable("DEPENDENCY_UNAVAILABLE", "Service nguồn trả về rỗng");
			}
			return body;
		} catch (RestClientResponseException ex) {
			if (ex.getStatusCode().is4xxClientError()) {
				throw new UpstreamRejected(HttpStatus.valueOf(ex.getStatusCode().value()));
			}
			throw ApiException.unavailable("DEPENDENCY_UNAVAILABLE", "Service nguồn chưa sẵn sàng");
		} catch (ApiException ex) {
			throw ex;
		} catch (RuntimeException ex) {
			throw ApiException.unavailable("DEPENDENCY_UNAVAILABLE", "Không kết nối được service nguồn");
		}
	}

	private static final class UpstreamRejected extends RuntimeException {
		private final HttpStatus status;

		UpstreamRejected(HttpStatus status) {
			super(null, null, false, false);
			this.status = status;
		}
	}
}
