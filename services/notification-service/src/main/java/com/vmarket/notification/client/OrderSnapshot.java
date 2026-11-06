package com.vmarket.notification.client;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Dữ liệu đơn hàng Notification cần (người mua, gian hàng, tổng tiền), đọc từ
 * {@code GET /internal/notification/orders/{id}} của order-service. Endpoint đó cố ý
 * không trả thông tin cá nhân của người nhận.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderSnapshot(
		String id,
		String userId,
		String status,
		BigDecimal totalAmount,
		String paymentMethod,
		List<String> shopIds) {

	public OrderSnapshot {
		shopIds = shopIds == null ? List.of() : shopIds.stream().filter(s -> s != null).distinct().toList();
	}

	public boolean isCod() {
		return paymentMethod == null || "COD".equalsIgnoreCase(paymentMethod);
	}
}
