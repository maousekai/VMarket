package com.vmarket.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Yêu cầu cấp access token mới bằng refresh token (FR-AUTH-02).
 */
@Schema(name = "RefreshRequest")
public record RefreshRequest(

		@Schema(example = "e30cJ1s...")
		@NotBlank(message = "refreshToken không được để trống")
		String refreshToken) {
}
