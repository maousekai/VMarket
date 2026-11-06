package com.vmarket.order.client;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.vmarket.order.exception.ApiException;

import lombok.extern.slf4j.Slf4j;

/**
 * Gọi API của shop-service để xác thực thông tin gian hàng khi Seller xử lý đơn (FR-ORD-04).
 */
@Slf4j
public class ShopServiceClient {

	private final RestClient restClient;

	public ShopServiceClient(RestClient restClient) {
		this.restClient = restClient;
	}

	public ShopView getMyShop(String bearerToken) {
		try {
			ShopView shop = restClient.get()
					.uri("/api/shops/me")
					.header("Authorization", bearerToken)
					.retrieve()
					.body(ShopView.class);
			if (shop == null) {
				log.error("shop-service trả body rỗng cho GET /api/shops/me");
				throw upstreamError();
			}
			return shop;
		} catch (RestClientResponseException ex) {
			throw translate(ex);
		} catch (ResourceAccessException ex) {
			log.error("Không kết nối được shop-service: {}", ex.getMessage());
			throw unavailable();
		} catch (RestClientException ex) {
			log.error("Response không hợp lệ từ shop-service: {}", ex.getMessage());
			throw upstreamError();
		}
	}

	private ApiException translate(RestClientResponseException ex) {
		HttpStatusCode status = ex.getStatusCode();
		if (status.is5xxServerError()) {
			log.error("shop-service trả lỗi HTTP {}", status.value());
			return unavailable();
		}
		var known = HttpStatus.resolve(status.value());
		if (known != null && known.is4xxClientError()) {
			if (known == HttpStatus.NOT_FOUND) {
				return ApiException.notFound("SHOP_NOT_FOUND", "Bạn chưa có gian hàng hoặc gian hàng không tồn tại");
			}
			return new ApiException("SHOP_ACCESS_DENIED", known, "Không thể truy cập gian hàng");
		}
		return upstreamError();
	}

	private static ApiException unavailable() {
		return new ApiException("SHOP_SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
				"Hệ thống quản lý gian hàng đang bận, vui lòng thử lại");
	}

	private static ApiException upstreamError() {
		return new ApiException("SHOP_SERVICE_ERROR", HttpStatus.BAD_GATEWAY,
				"Lỗi kết nối tới hệ thống gian hàng");
	}

	public record ShopView(String id, String ownerId, String name, String status) {
	}
}
