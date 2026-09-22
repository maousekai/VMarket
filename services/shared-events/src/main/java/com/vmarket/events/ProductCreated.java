package com.vmarket.events;

import java.util.List;

/** Snapshot đầy đủ để Search/Recommendation đồng bộ sản phẩm vừa tạo. */
public record ProductCreated(
		String productId,
		String shopId,
		String name,
		Long price,
		String status,
		List<String> imageUrls,
		int schemaVersion,
		String currency,
		String description,
		String categoryId,
		String brandId,
		Long maxPrice,
		List<ProductVariantSnapshot> variants,
		double ratingAverage,
		long ratingCount,
		long soldCount,
		long availableStock,
		boolean catalogVisible) {

	/** Constructor tương thích ngược với payload v1. */
	public ProductCreated(String productId, String shopId, String name, Long price,
			String status, List<String> imageUrls) {
		this(productId, shopId, name, price, status, imageUrls, 1, "VND", null, null, null,
				price, List.of(), 0, 0, 0, 0, "ACTIVE".equals(status));
	}
}
