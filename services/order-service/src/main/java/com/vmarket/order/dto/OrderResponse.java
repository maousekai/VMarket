package com.vmarket.order.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.vmarket.order.entity.Order;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Một đơn hàng trả ra cho client (FR-ORDER-01/02).
 *
 * <p>Item được nạp EAGER cùng đơn nên {@code from(order)} tự đủ dữ liệu; phương
 * thức nhận {@code items} tách riêng dùng khi service nạp item bằng query riêng.
 */
@Schema(name = "OrderResponse", description = "Đơn hàng")
public record OrderResponse(

		@Schema(example = "01JC4X9Q2W5R8T6V8N0P4S6A2B") String id,

		@Schema(example = "PENDING") String status,

		@Schema(example = "Nguyễn Văn An") String recipientName,

		@Schema(example = "0912345678") String phone,

		@Schema(example = "Đà Nẵng") String province,

		@Schema(example = "Hải Châu") String district,

		@Schema(example = "Thạch Thang") String ward,

		@Schema(example = "54 Nguyễn Lương Bằng") String streetAddress,

		String note,

		@Schema(example = "1250000.00") BigDecimal totalAmount,

		@Schema(example = "01JC4X9Q2W5R8T6V8N0P4S6A2C") Instant createdAt,

		List<OrderItemResponse> items) {

	public static OrderResponse from(Order order) {
		return new OrderResponse(
				order.getId(),
				order.getStatus().name(),
				order.getRecipientName(),
				order.getPhone(),
				order.getProvince(),
				order.getDistrict(),
				order.getWard(),
				order.getStreetAddress(),
				order.getNote(),
				order.getTotalAmount(),
				order.getCreatedAt(),
				order.getItems().stream().map(OrderItemResponse::from).toList());
	}
}