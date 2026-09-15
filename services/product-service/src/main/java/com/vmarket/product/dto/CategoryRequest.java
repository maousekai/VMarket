package com.vmarket.product.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CategoryRequest(
		@NotBlank @Size(max = 120) String name,
		String parentId,
		@Min(0) int sortOrder,
		boolean active) {
}
