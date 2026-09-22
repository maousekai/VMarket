package com.vmarket.cart.service;

import java.util.Optional;

import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.vmarket.cart.config.ProductCatalogProperties;
import com.vmarket.cart.dto.ProductSnapshot;
import com.vmarket.cart.exception.ProductCatalogUnavailableException;

import lombok.extern.slf4j.Slf4j;

/**
 * Gọi product-service qua REST ({@code GET /api/products/{productId}}).
 *
 * <p>Gửi kèm {@code X-Internal-Api-Key} — cùng quy ước internal API key của
 * docker-compose (user-service cũng dùng). 404 nghĩa là sản phẩm đã bị xoá →
 * trả {@code Optional#empty()} (không phải lỗi hạ tầng).
 */
@Slf4j
@Component
public class RestClientProductCatalogClient implements ProductCatalogClient {

	private static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

	private final RestClient restClient;

	public RestClientProductCatalogClient(RestClient.Builder restClientBuilder,
			ProductCatalogProperties properties) {
		this.restClient = restClientBuilder
				.baseUrl(properties.getBaseUrl())
				.defaultHeader(INTERNAL_API_KEY_HEADER, properties.getApiKey())
				.build();
	}

	@Override
	public Optional<ProductSnapshot> findProduct(String productId) {
		try {
			ProductSnapshot snapshot = restClient.get()
					.uri("/api/products/{productId}", productId)
					.retrieve()
					.body(ProductSnapshot.class);
			return Optional.ofNullable(snapshot);
		} catch (HttpStatusCodeException ex) {
			HttpStatusCode status = ex.getStatusCode();
			if (status.value() == 404) {
				// Sản phẩm không còn tồn tại (đã xoá/gỡ) — đây là dữ liệu, không phải lỗi.
				return Optional.empty();
			}
			log.warn("product-service trả lỗi {} cho productId={}", status.value(), productId);
			throw new ProductCatalogUnavailableException(
					"product-service trả lỗi HTTP " + status.value(), ex);
		} catch (ResourceAccessException ex) {
			log.warn("Không kết nối được product-service: {}", ex.getMessage());
			throw new ProductCatalogUnavailableException(
					"Không kết nối được product-service", ex);
		}
	}
}
