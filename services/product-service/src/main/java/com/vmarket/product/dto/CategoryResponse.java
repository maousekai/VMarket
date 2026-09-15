package com.vmarket.product.dto;

import java.util.List;

public record CategoryResponse(
		String id,
		String name,
		String slug,
		String parentId,
		boolean active,
		int sortOrder,
		List<CategoryResponse> children) {
}
