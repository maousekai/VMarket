package com.vmarket.events;

import java.math.BigDecimal;
import java.util.List;

/**
 * Payload sự kiện {@code ProductUpdated} (Product Catalog phát, AI Search /
 * Recommendation nhận để đồng bộ chỉ mục — FR-SRCH-04).
 */
public record ProductUpdated(
		String productId,
		String shopId,
		String name,
		BigDecimal price,
		String status,
		List<String> imageUrls) {
}
