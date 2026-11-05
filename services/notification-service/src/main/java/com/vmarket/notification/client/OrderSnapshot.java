package com.vmarket.notification.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Phần dữ liệu đơn hàng Notification cần (người mua, gian hàng, tổng tiền), đọc từ
 * {@code GET /internal/delivery/orders/{id}} của order-service.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderSnapshot(
		String id,
		String userId,
		String status,
		BigDecimal totalAmount,
		String paymentMethod,
		List<Item> items) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Item(String productId, String shopId, int quantity) {
	}

	/** Danh sách shopId không trùng, giữ thứ tự xuất hiện. */
	public List<String> shopIds() {
		if (items == null) {
			return List.of();
		}
		return items.stream().map(Item::shopId).filter(Objects::nonNull).distinct().toList();
	}

	public boolean isCod() {
		return paymentMethod == null || "COD".equalsIgnoreCase(paymentMethod);
	}
}
