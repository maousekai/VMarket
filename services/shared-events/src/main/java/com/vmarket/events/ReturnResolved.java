package com.vmarket.events;

import java.math.BigDecimal;
import java.util.List;

/**
 * Order Service phát khi yêu cầu trả hàng được chốt.
 * {@code restock=true} chỉ được gửi sau khi hàng đã được thu hồi và có thể nhập kho.
 */
public record ReturnResolved(
		String returnId,
		String orderId,
		BigDecimal refundAmount,
		boolean restock,
		List<StockItem> items) {

	public ReturnResolved(String returnId, String orderId, boolean restock, List<StockItem> items) {
		this(returnId, orderId, BigDecimal.ZERO, restock, items);
	}
}
