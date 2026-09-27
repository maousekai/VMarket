package com.vmarket.cart.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Một item trong giỏ hàng trả ra cho client.
 *
 * @param productId sản phẩm
 * @param variantId biến thể (null nếu sản phẩm không có biến thể)
 * @param shopId    gian hàng bán — client dùng để nhóm hiển thị (FR-CART-02)
 * @param quantity  số lượng
 * @param unitPrice đơn giá snapshot tại thời điểm thêm vào giỏ
 * @param lineTotal tổng tiền dòng = unitPrice * quantity
 * @param addedAt   thời điểm thêm vào giỏ
 * @param updatedAt thời điểm cập nhật gần nhất
 */
public record CartItemDto(
		String productId,
		String variantId,
		String shopId,
		int quantity,
		BigDecimal unitPrice,
		BigDecimal lineTotal,
		Instant addedAt,
		Instant updatedAt) {
}
