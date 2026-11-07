package com.vmarket.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DisputeReturnRequest(
		@NotBlank @Size(max = 500) String reason) {
}
