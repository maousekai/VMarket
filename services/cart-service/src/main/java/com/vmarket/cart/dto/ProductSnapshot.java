package com.vmarket.cart.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Snapshot mới nhất của một sản phẩm lấy từ product-service qua API đồng bộ
 * (FR-CART-03). Đây là mô hình NỘI BỘ mà {@code CheckoutService} kiểm tra; JSON
 * thật của {@code GET /api/products/{productId}} (PBL6-15) được đọc bằng
 * {@link ProductDetailPayload} rồi chuyển sang đây.
 *
 * @param productId sản phẩm
 * @param shopId    gian hàng bán
 * @param price     giá bán hiện tại (khi sản phẩm không có biến thể)
 * @param stock     tồn kho hiện tại (khi sản phẩm không có biến thể)
 * @param variants  danh sách biến thể (khi sản phẩm có biến thể)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductSnapshot(
		String productId,
		String shopId,
		BigDecimal price,
		Integer stock,
		List<VariantSnapshot> variants) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record VariantSnapshot(
			String variantId,
			BigDecimal price,
			Integer stock) {
	}
}
