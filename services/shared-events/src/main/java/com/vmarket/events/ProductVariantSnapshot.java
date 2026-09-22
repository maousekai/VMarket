package com.vmarket.events;

import java.util.Map;

/** Biến thể nằm trong snapshot ProductCreated/ProductUpdated schema v4. */
public record ProductVariantSnapshot(
		String variantId,
		String sku,
		Map<String, String> attributes,
		long price,
		String currency,
		long availableStock,
		long soldCount) {
}
