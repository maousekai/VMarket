package com.vmarket.order.dto;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Dữ liệu người dùng gửi lên khi đặt hàng (FR-ORDER-01).
 *
 * <p>Cố ý <b>nhỏ</b>: chỉ gồm địa chỉ và ghi chú. Danh sách sản phẩm lấy từ giỏ
 * hàng phía server (cart-service), người dùng KHÔNG gửi giá hay số lượng — nếu
 * chấp nhận các trường đó từ client thì kẻ xấu đặt 10.000đ cho món 1.000.000đ
 * (mass assignment). Id địa chỉ dùng để nạp địa chỉ mặc định từ user-service,
 * so khớp {@code userId} chống IDOR.
 */
@Schema(name = "PlaceOrderRequest", description = "Yêu cầu đặt hàng từ giỏ hiện có")
public record PlaceOrderRequest(

		@Schema(example = "01JBQ9YDX7K3M8N5P2R4T6V8W0", description = "Địa chỉ giao hàng trong sổ địa chỉ")
		@NotBlank(message = "Địa chỉ giao hàng không được để trống")
		@Size(max = 26, message = "addressId không hợp lệ")
		String addressId,

		@Schema(example = "Giao giờ hành chính")
		@Size(max = 255, message = "Ghi chú tối đa 255 ký tự")
		String note) {
}