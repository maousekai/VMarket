package com.vmarket.events;

/** Product Catalog phát để Notification Service báo kết quả kiểm duyệt cho Seller. */
public record ProductModerated(
		String productId,
		String shopId,
		String sellerId,
		boolean removed,
		String reason) {
}
