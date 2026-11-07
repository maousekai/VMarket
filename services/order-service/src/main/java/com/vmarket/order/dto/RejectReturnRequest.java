package com.vmarket.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectReturnRequest(
		@NotBlank @Size(max = 500) String reason) {
}
