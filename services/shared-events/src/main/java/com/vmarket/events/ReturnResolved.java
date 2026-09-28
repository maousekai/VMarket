package com.vmarket.events;

import java.util.List;

/**
 * Order Service phát khi yêu cầu trả hàng được chốt.
 * {@code restock=true} chỉ được gửi sau khi hàng đã được thu hồi và có thể nhập kho.
 */
public record ReturnResolved(String returnId, String orderId, boolean restock, List<StockItem> items) {
}
