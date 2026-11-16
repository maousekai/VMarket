package com.vmarket.cart.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Snapshot chuẩn hóa mới nhất của sản phẩm dùng bởi cart-service (FR-CART-03).
 * {@code RestClientProductCatalogClient} unwraps the product-service detail envelope
 * and maps its {@code id} fields into this DTO's {@code productId}/{@code variantId}.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} để product-service bổ sung
 * trường mới không làm cart-service đọc lỗi.
 *
 * @param productId sản phẩm
 * @param shopId    gian hàng bán
 * @param price     giá bán hiện tại (khi sản phẩm không có biến thể)
 * @param stock     tồn kho hiện tại (khi sản phẩm không có biến thể)
 * @param variants  danh sách biến thể (khi sản phẩm có biến thể)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductSnapshot(
		@JsonAlias("id")
		String productId,
		String shopId,
		BigDecimal price,
		@JsonAlias("availableStock")
		Integer stock,
		List<VariantSnapshot> variants) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record VariantSnapshot(
			@JsonAlias("id")
			String variantId,
			BigDecimal price,
			@JsonAlias("availableStock")
			Integer stock) {
	}
}
