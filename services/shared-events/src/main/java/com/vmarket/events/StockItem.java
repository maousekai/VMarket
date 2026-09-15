package com.vmarket.events;

/** Một dòng tồn kho trong kết quả giữ/hoàn kho của đơn hàng. */
public record StockItem(String productId, String variantId, int quantity) {
}
