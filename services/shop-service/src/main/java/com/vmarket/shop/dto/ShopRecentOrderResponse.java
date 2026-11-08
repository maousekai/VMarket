package com.vmarket.shop.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Một đơn gần đây của gian hàng; tiền và số lượng chỉ tính item thuộc gian hàng. */
public record ShopRecentOrderResponse(
		String orderId,
		String status,
		Instant createdAt,
		BigDecimal shopSubtotal,
		long itemCount) {
}
