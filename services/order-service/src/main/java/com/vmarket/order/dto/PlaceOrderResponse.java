package com.vmarket.order.dto;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Kết quả đặt hàng (FR-ORD-01): mỗi shop trong giỏ thành một đơn, mọi đơn chung {@code checkoutId}.
 */
@Schema(name = "PlaceOrderResponse", description = "Kết quả checkout: các đơn tách theo gian hàng")
public record PlaceOrderResponse(

		@Schema(example = "01JC4X9Q2W5R8T6V8N0P4S6A2B", description = "Mã chung của các đơn trong lần checkout") String checkoutId,

		@Schema(example = "250000.00", description = "Tổng tiền của tất cả các đơn") BigDecimal totalAmount,

		@Schema(description = "Các đơn theo thứ tự nhóm shop của giỏ") List<OrderResponse> orders) {

	public static PlaceOrderResponse of(String checkoutId, List<OrderResponse> orders) {
		BigDecimal total = orders.stream()
				.map(OrderResponse::totalAmount)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		return new PlaceOrderResponse(checkoutId, total, orders);
	}
}
