package com.vmarket.product.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

public record InventoryRequest(@NotBlank String orderId, @NotEmpty List<@Valid InventoryItem> items) {
	public record InventoryItem(@NotBlank String productId, @NotBlank String variantId, @Min(1) int quantity) {
	}
}
