package com.vmarket.shop.dto;

/** Chủ gian hàng cho API nội bộ của notification-service (PBL6-23). */
public record ShopOwnerResponse(String shopId, String ownerId, String name) {
}
