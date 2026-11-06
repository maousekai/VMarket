package com.vmarket.notification.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Chủ gian hàng từ {@code GET /internal/shops/{shopId}/owner} của shop-service. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShopOwnerInfo(String shopId, String ownerId, String name) {
}
