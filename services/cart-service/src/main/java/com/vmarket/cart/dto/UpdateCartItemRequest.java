package com.vmarket.cart.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request cập nhật số lượng một item trong giỏ.
 *
 * @param quantity số lượng mới, tối thiểu 1 (muốn bỏ thì dùng DELETE item)
 */
public record UpdateCartItemRequest(
		@NotNull(message = "quantity không được để trống")
		@Min(value = 1, message = "quantity phải lớn hơn hoặc bằng 1") Integer quantity) {
}
