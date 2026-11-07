package com.vmarket.order.dto;

import java.math.BigDecimal;

import com.vmarket.order.entity.OrderReturnItem;

public record ReturnItemResponse(
		String id,
		String orderItemId,
		String productId,
		String variantId,
		int quantity,
		BigDecimal unitPrice,
		BigDecimal lineTotal) {

	public static ReturnItemResponse from(OrderReturnItem item) {
		return new ReturnItemResponse(
				item.getId(),
				item.getOrderItemId(),
				item.getProductId(),
				item.getVariantId(),
				item.getQuantity(),
				item.getUnitPrice(),
				item.getLineTotal());
	}
}
