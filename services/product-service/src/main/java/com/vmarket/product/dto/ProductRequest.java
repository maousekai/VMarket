package com.vmarket.product.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.vmarket.product.model.ProductStatus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ProductRequest(
		@NotBlank String shopId,
		@NotBlank @Size(max = 200) String name,
		@NotBlank @Size(max = 10000) String description,
		@NotEmpty @Size(max = 9) List<@NotBlank String> imageUrls,
		@NotBlank String categoryId,
		String brandId,
		@NotNull ProductStatus status,
		@NotEmpty List<@Valid VariantRequest> variants) {

	public record VariantRequest(
			String id,
			@NotBlank @Size(max = 100) String sku,
			@NotNull Map<@NotBlank String, @NotBlank String> attributes,
			@NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal price,
			@Min(0) long stock) {
	}
}
