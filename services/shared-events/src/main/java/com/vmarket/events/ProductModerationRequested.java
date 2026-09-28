package com.vmarket.events;

/** Product Catalog phát khi Seller gửi lại sản phẩm đã chỉnh sửa để Admin duyệt. */
public record ProductModerationRequested(String productId, String shopId, String sellerId) {
}
