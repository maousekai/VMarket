package com.vmarket.order.dto;

import java.math.BigDecimal;

import com.vmarket.order.entity.OrderItem;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Một dòng hàng trong đơn trả ra cho client. Snapshot — không tham chiếu lại sản
 * phẩm hiện tại (giá/tên có thể đã đổi).
 */
@Schema(name = "OrderItemResponse", description = "Dòng hàng trong đơn")
public record OrderItemResponse(

		@Schema(example = "01JBQ9YDX7K3M8N5P2R4T6V8W1") String productId,

		@Schema(example = "01JBQ9YDX7K3M8N5P2R4T6V8W2") String variantId,

		@Schema(example = "01JBQ9YDX7K3M8N5P2R4T6V8W3") String shopId,

		@Schema(example = "2") int quantity,

		@Schema(example = "250000.00") BigDecimal unitPrice,

		@Schema(example = "500000.00") BigDecimal lineTotal) {

	public static OrderItemResponse from(OrderItem item) {
		return new OrderItemResponse(
				item.getProductId(),
				item.getVariantId(),
				item.getShopId(),
				item.getQuantity(),
				item.getUnitPrice(),
				item.getLineTotal());
	}
}