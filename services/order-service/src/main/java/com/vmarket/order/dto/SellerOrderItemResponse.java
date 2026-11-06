package com.vmarket.order.dto;

import java.math.BigDecimal;

import com.vmarket.order.entity.OrderItem;

public record SellerOrderItemResponse(
		String id,
		String productId,
		String variantId,
		int quantity,
		BigDecimal unitPrice,
		BigDecimal lineTotal) {

	public static SellerOrderItemResponse from(OrderItem item) {
		return new SellerOrderItemResponse(
				item.getId(),
				item.getProductId(),
				item.getVariantId(),
				item.getQuantity(),
				item.getUnitPrice(),
				item.getLineTotal());
	}
}
