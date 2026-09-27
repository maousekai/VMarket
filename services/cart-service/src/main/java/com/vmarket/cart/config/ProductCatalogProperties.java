package com.vmarket.cart.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Địa chỉ product-service để kiểm tra giá/tồn kho trước thanh toán (FR-CART-03).
 *
 * <p>Theo kiến trúc (docs/event-bus.md §1): service KHÔNG đọc CSDL của service
 * khác — dữ liệu giá/tồn kho "mới nhất" được lấy qua API đồng bộ.
 */
@ConfigurationProperties(prefix = "app.products")
public class ProductCatalogProperties {

	/** Base URL của product-service, vd: http://product-service:8084. */
	private String baseUrl = "http://localhost:8084";

	/**
	 * Internal API key gửi kèm header {@code X-Internal-Api-Key} khi gọi
	 * product-service. Dev dùng giá trị default; prod phải đặt qua biến môi trường.
	 */
	private String apiKey = "dev-only-internal-api-key-change-me-0123456789";

	public String getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public String getApiKey() {
		return apiKey;
	}

	public void setApiKey(String apiKey) {
		this.apiKey = apiKey;
	}
}
