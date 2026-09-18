package com.vmarket.auth.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/** Một phiên (thiết bị) đang hoạt động của user — FR-AUTH-06. */
@Schema(name = "SessionSummary")
public record SessionSummary(
		@Schema(example = "01JRX8Z0M0P8QF3W9K2T7Y6C4B") String id,
		@Schema(description = "User-Agent lúc phát/xoay vòng token gần nhất, có thể null nếu thiếu header",
				example = "Mozilla/5.0 ...") String userAgent,
		@Schema(description = "IP best-effort, có thể null", example = "203.0.113.10") String ipAddress,
		Instant createdAt,
		Instant lastUsedAt,
		@Schema(description = "true nếu đây là phiên của chính request đang gọi") boolean current) {
}
