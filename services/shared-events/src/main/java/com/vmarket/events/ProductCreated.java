package com.vmarket.events;

import java.math.BigDecimal;
import java.util.List;

/**
 * Payload sự kiện {@code ProductCreated} (Product Catalog phát, AI Search /
 * Recommendation nhận để đồng bộ chỉ mục — FR-SRCH-04).
 *
 * <p>Đây là phiên bản demo tối thiểu (PBL6-39). Khi triển khai Product Catalog
 * thật, cần bổ sung các trường: Mô tả, danh mục, biến thể, thuộc tính... và
 * phiên bản hoá schema nếu cần để khả năng tương thích ngược.
 */
public record ProductCreated(
		String productId,
		String shopId,
		String name,
		BigDecimal price,
		String status,
		List<String> imageUrls) {
}