package com.vmarket.shop.dto;

/** Số lượng gian hàng theo trạng thái cho tab và badge của trang Admin. */
public record ShopCountsResponse(
		long pending,
		long active,
		long rejected,
		long suspended,
		long total) {
}
