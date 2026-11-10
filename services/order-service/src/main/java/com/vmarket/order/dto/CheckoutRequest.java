package com.vmarket.order.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Đặt hàng từ những dòng ĐÃ CHỌN trong giỏ (PBL6-29/30). Giỏ có hàng của nhiều gian hàng
 * thì tạo một đơn cho mỗi gian hàng.
 *
 * <p>Client chỉ gửi định danh dòng giỏ ({@code productId} + {@code variantId}); số lượng và giá
 * vẫn lấy từ giỏ phía server để không thể sửa giá (xem {@link PlaceOrderRequest}).
 * {@code items} rỗng / null nghĩa là đặt toàn bộ giỏ.
 */
@Schema(name = "CheckoutRequest", description = "Đặt hàng các dòng đã chọn trong giỏ, tách đơn theo gian hàng")
public record CheckoutRequest(
		@NotBlank(message = "Địa chỉ giao hàng không được để trống")
		@Size(max = 26, message = "addressId không hợp lệ")
		String addressId,
		@Size(max = 255, message = "Ghi chú tối đa 255 ký tự")
		String note,
		@Pattern(regexp = "COD|PAYOS") String paymentMethod,
		@Size(max = 50, message = "Tối đa 50 dòng hàng") List<@Valid Item> items) {

	public record Item(
			@NotBlank @Size(max = 64) String productId,
			@Size(max = 64) String variantId) {
	}

	public PlaceOrderRequest toPlaceOrder() {
		return new PlaceOrderRequest(addressId, note, paymentMethod);
	}
}
