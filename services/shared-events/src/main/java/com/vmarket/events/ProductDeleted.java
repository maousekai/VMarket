package com.vmarket.events;

/** Payload phát khi Product Catalog ẩn/xóa một sản phẩm. */
public record ProductDeleted(String productId, String shopId) {
}
