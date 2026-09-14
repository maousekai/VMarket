package com.vmarket.events;

import java.math.BigDecimal;
import java.util.Map;

/** Biến thể nằm trong snapshot ProductCreated/ProductUpdated schema v2. */
public record ProductVariantSnapshot(
		String variantId,
		String sku,
		Map<String, String> attributes,
		BigDecimal price,
		long availableStock,
		long soldCount) {
}
