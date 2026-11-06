package com.vmarket.events;

import java.util.List;

/**
 * Order Service phát khi Buyer tạo yêu cầu trả hàng (FR-ORD-06).
 */
public record ReturnRequested(
		String returnId,
		String orderId,
		String shopId,
		String userId,
		String reason,
		List<StockItem> items) {
}

