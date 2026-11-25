package com.vmarket.order.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.vmarket.order.exception.ApiException;

import lombok.extern.slf4j.Slf4j;

/**
 * Đọc giá hiện tại của sản phẩm từ product-service ({@code GET /api/products/{id}})
 * để định giá lại đơn hàng lúc đặt (PBL6-48): giá trong giỏ chỉ là snapshot, không
 * phải nguồn sự thật.
 *
 * <p>404 → {@link Optional#empty()} (sản phẩm đã bị gỡ). Mọi lỗi khác (không kết nối
 * được, 5xx, body sai hợp đồng) → 503 {@code PRODUCT_SERVICE_UNAVAILABLE}: không biết
 * giá thì không tạo đơn.
 */
@Slf4j
public class ProductServiceClient {

	private final RestClient restClient;

	public ProductServiceClient(RestClient restClient) {
		this.restClient = restClient;
	}

	public Optional<ProductPrices> findProduct(String productId) {
		ProductDetailPayload payload;
		try {
			payload = restClient.get()
					.uri("/api/products/{productId}", productId)
					.retrieve()
					.body(ProductDetailPayload.class);
		} catch (RestClientResponseException ex) {
			if (ex.getStatusCode().value() == 404) {
				return Optional.empty();
			}
			log.warn("product-service trả HTTP {} cho productId={}", ex.getStatusCode().value(), productId);
			throw unavailable();
		} catch (RestClientException ex) {
			log.warn("Không đọc được giá từ product-service cho productId={}: {}", productId,
					ex.getClass().getSimpleName());
			throw unavailable();
		}
		if (payload == null || payload.product() == null) {
			log.warn("product-service trả body rỗng cho productId={}", productId);
			throw unavailable();
		}
		List<Variant> variants = payload.product().variants() == null ? List.of() : payload.product().variants();
		return Optional.of(new ProductPrices(payload.product().id(), payload.product().shopId(), variants));
	}

	private static ApiException unavailable() {
		return ApiException.unavailable("PRODUCT_SERVICE_UNAVAILABLE",
				"Chưa kiểm tra được giá sản phẩm, vui lòng thử lại sau");
	}

	/** Gian hàng bán và giá theo biến thể của một sản phẩm. */
	public record ProductPrices(String productId, String shopId, List<Variant> variants) {

		/**
		 * Giá của dòng hàng: theo {@code variantId}; không có {@code variantId} thì chỉ
		 * xác định được khi sản phẩm có đúng một biến thể (cùng quy tắc cart-service).
		 *
		 * @return {@code null} khi không xác định được giá
		 */
		public BigDecimal priceOf(String variantId) {
			if (variantId == null || variantId.isBlank()) {
				return variants.size() == 1 ? variants.get(0).price() : null;
			}
			// Không map sang price trước findFirst: giá null làm findFirst ném NPE (500).
			return variants.stream()
					.filter(v -> variantId.equals(v.id()))
					.findFirst()
					.map(Variant::price)
					.orElse(null);
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record ProductDetailPayload(Product product) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Product(String id, String shopId, List<Variant> variants) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Variant(String id, BigDecimal price) {
	}
}
