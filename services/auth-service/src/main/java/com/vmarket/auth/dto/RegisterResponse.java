package com.vmarket.auth.dto;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Kết quả đăng ký. Chưa trả JWT — đăng nhập là FR-AUTH-02 (PBL6-43).
 */
@Schema(name = "RegisterResponse")
public record RegisterResponse(
		@Schema(example = "01JRX8Z0M0P8QF3W9K2T7Y6C4B") String id,
		@Schema(example = "an.nguyen@example.com") String email,
		@Schema(example = "an.nguyen") String username,
		@Schema(example = "PENDING") AccountStatus status,
		@Schema(example = "[\"BUYER\"]") List<String> roles,
		Instant createdAt) {
}
