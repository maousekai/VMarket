package com.vmarket.cart.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Hình dạng "trên dây" của {@code GET /api/products/{id}}, chấp nhận CẢ HAI dạng:
 * <ul>
 *   <li>Dạng thật của product-service: {@code {"product":{id, shopId, status, variants[{id, price,
 *       stock, availableStock}], minPrice, availableStock, ...}, "similarProducts":[...]}}.</li>
 *   <li>Dạng phẳng cũ ({@link ProductSnapshot}): {@code {productId, shopId, price, stock,
 *       variants[{variantId, price, stock}]}}.</li>
 * </ul>
 * Chỉ dùng nội bộ để map sang {@link ProductSnapshot}; mọi trường đều tùy chọn.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductCatalogWire(
		Detail product,
		String productId,
		String shopId,
		BigDecimal price,
		Integer stock,
		List<Variant> variants) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Detail(
			String id,
			String shopId,
			String status,
			Boolean moderationRemoved,
			BigDecimal minPrice,
			Long availableStock,
			List<Variant> variants) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Variant(
			String id,
			String variantId,
			BigDecimal price,
			Long stock,
			Long availableStock) {
	}
}
