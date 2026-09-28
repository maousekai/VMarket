package com.vmarket.product.dto;

import jakarta.validation.constraints.Size;

public record ModerationRequest(boolean removed, @Size(max = 1000) String reason) {
}
