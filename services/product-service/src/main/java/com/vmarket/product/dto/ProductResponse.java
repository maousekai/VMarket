package com.vmarket.product.dto;

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
		String currency,
		List<VariantResponse> variants,
		Long minPrice,
		Long maxPrice,
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
			long price,
			String currency,
			long stock,
			long reservedStock,
			long availableStock,
			long soldCount) {
	}
}
