package com.vmarket.cart.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import com.vmarket.cart.CartLimits;

/**
 * Request cập nhật số lượng một item trong giỏ.
 *
 * @param quantity số lượng mới, từ 1 tới {@link CartLimits#MAX_QUANTITY_PER_ITEM}
 *                 (muốn bỏ thì dùng DELETE item)
 */
public record UpdateCartItemRequest(
		@NotNull(message = "quantity không được để trống")
		@Min(value = 1, message = "quantity phải lớn hơn hoặc bằng 1")
		@Max(value = CartLimits.MAX_QUANTITY_PER_ITEM,
				message = "quantity tối đa " + CartLimits.MAX_QUANTITY_PER_ITEM + " cho mỗi sản phẩm")
		Integer quantity) {
}
