package com.vmarket.cart.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Một vấn đề phát hiện khi kiểm tra giỏ trước thanh toán (FR-CART-03).
 *
 * @param productId        sản phẩm
 * @param variantId        biến thể
 * @param type             loại vấn đề: PRICE_CHANGED | OUT_OF_STOCK | NOT_FOUND |
 *                         SERVICE_UNAVAILABLE
 * @param message          thông điệp hiển thị cho người dùng
 * @param cartPrice        giá snapshot trong giỏ
 * @param currentPrice     giá mới nhất từ product-service (khi có)
 * @param requestedQuantity số lượng đang có trong giỏ
 * @param availableStock   tồn kho hiện tại (khi có)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckoutIssueDto(
		String productId,
		String variantId,
		String type,
		String message,
		BigDecimal cartPrice,
		BigDecimal currentPrice,
		Integer requestedQuantity,
		Integer availableStock) {
}
