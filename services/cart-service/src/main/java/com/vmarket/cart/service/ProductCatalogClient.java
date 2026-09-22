package com.vmarket.cart.service;

import java.util.Optional;

import com.vmarket.cart.dto.ProductSnapshot;

/**
 * Cổng lấy snapshot mới nhất của sản phẩm từ Product Catalog (FR-CART-03).
 *
 * <p>Theo kiến trúc database-per-service (docs/event-bus.md §1), cart-service
 * KHÔNG đọc CSDL của product-service — giá/tồn kho "mới nhất" phải lấy qua API
 * đồng bộ. Tách interface để test dễ thay thế bằng mock.
 */
public interface ProductCatalogClient {

	/**
	 * Lấy snapshot sản phẩm theo id.
	 *
	 * @return {@link Optional#empty()} nếu sản phẩm không tồn tại (404)
	 * @throws ProductCatalogUnavailableException khi product-service không trả lời
	 *                                            hoặc lỗi ngoài 404
	 */
	Optional<ProductSnapshot> findProduct(String productId);
}
