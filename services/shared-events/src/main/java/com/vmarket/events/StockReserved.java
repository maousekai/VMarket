package com.vmarket.events;

import java.util.List;

/** Product Catalog phát sau khi giữ đủ tồn kho cho một đơn hàng. */
public record StockReserved(String orderId, List<StockItem> items) {
}
