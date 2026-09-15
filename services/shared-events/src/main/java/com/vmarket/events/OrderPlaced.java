package com.vmarket.events;

import java.util.List;

/** Order Service phát để Product Catalog tạm giữ tồn kho. */
public record OrderPlaced(String orderId, List<StockItem> items) {
}
