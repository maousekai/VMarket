package com.vmarket.order.dto;

import jakarta.validation.constraints.Size;

public record AdminResolveReturnRequest(
		boolean approved,
		boolean restock,
		@Size(max = 500) String note) {
}
