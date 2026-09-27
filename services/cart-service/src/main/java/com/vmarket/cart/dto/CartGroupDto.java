package com.vmarket.cart.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Một nhóm item theo gian hàng trong giỏ hàng (FR-CART-02).
 *
 * @param shopId   gian hàng
 * @param items    các item thuộc gian hàng này
 * @param subtotal tạm tính của cả nhóm = tổng lineTotal của các item
 */
public record CartGroupDto(
		String shopId,
		List<CartItemDto> items,
		BigDecimal subtotal) {
}
