package com.vmarket.product.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Bounded snapshot page pushed by Shop Service for bootstrap/reconciliation. */
public record ShopAccessSyncRequest(
		@NotEmpty @Size(max = 500) List<@Valid ShopAccessSnapshot> shops) {
	public record ShopAccessSnapshot(
			@NotBlank String shopId,
			@NotBlank String sellerId,
			boolean active,
			@NotNull Instant updatedAt) {
	}
}
