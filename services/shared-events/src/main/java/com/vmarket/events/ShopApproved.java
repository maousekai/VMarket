package com.vmarket.events;

/** Shop Service phát khi gian hàng được duyệt hoặc được mở lại. */
public record ShopApproved(String shopId, String sellerId) {
}
