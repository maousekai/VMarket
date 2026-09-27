package com.vmarket.cart.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Giỏ hàng của người dùng, nhóm theo gian hàng (FR-CART-01 + FR-CART-02).
 *
 * @param userId        người dùng sở hữu giỏ
 * @param groups        các nhóm theo gian hàng
 * @param totalQuantity tổng số lượng item (cộng dồn quantity)
 * @param totalAmount   tổng cộng cả giỏ = tổng subtotal các nhóm
 * @param updatedAt     thời điểm cập nhật gần nhất của giỏ
 */
public record CartResponse(
		String userId,
		List<CartGroupDto> groups,
		int totalQuantity,
		BigDecimal totalAmount,
		Instant updatedAt) {

	public static CartResponse empty(String userId) {
		return new CartResponse(userId, List.of(), 0, BigDecimal.ZERO, null);
	}
}
