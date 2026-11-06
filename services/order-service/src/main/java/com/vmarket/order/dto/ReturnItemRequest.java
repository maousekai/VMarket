package com.vmarket.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record ReturnItemRequest(
		@NotBlank String orderItemId,
		@NotBlank String productId,
		String variantId,
		@Min(1) int quantity) {
}
