package com.vmarket.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReturnItemRequest(
		@NotBlank String orderItemId,
		@NotBlank @Size(max = 64) String productId,
		@Size(max = 64) String variantId,
		@Min(1) int quantity) {
}
