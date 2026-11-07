package com.vmarket.order.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record CreateReturnRequest(
		@NotBlank @Size(max = 500) String reason,
		@Size(max = 5) List<String> evidenceUrls,
		@NotEmpty List<@Valid ReturnItemRequest> items) {
}
