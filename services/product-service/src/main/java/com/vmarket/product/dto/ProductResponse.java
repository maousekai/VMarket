package com.vmarket.product.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.vmarket.product.model.ProductStatus;

public record ProductResponse(
		String id,
		String shopId,
		String name,
		String description,
		List<String> imageUrls,
		String categoryId,
		String brandId,
		ProductStatus status,
		List<VariantResponse> variants,
		BigDecimal minPrice,
		BigDecimal maxPrice,
		long availableStock,
		double ratingAverage,
		long ratingCount,
		long soldCount,
		boolean moderationRemoved,
		String moderationReason,
		Instant createdAt,
		Instant updatedAt) {

	public record VariantResponse(
			String id,
			String sku,
			Map<String, String> attributes,
			BigDecimal price,
			long stock,
			long reservedStock,
			long availableStock,
			long soldCount) {
	}
}
