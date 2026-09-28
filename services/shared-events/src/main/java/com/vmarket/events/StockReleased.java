package com.vmarket.events;

import java.util.List;

/** Product Catalog phát sau khi giải phóng tồn kho đã giữ của một đơn hàng. */
public record StockReleased(String orderId, List<StockItem> items) {
}
