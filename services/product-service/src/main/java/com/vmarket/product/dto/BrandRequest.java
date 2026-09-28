package com.vmarket.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BrandRequest(@NotBlank @Size(max = 120) String name, boolean active) {
}
