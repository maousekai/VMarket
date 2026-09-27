package com.vmarket.auth.dto.internal;

import java.time.Instant;

import com.vmarket.auth.entity.AccountActivity;
import com.vmarket.auth.entity.AccountActivityType;

import io.swagger.v3.oas.annotations.media.Schema;

/** Một sự kiện trong lịch sử hoạt động cơ bản của tài khoản (FR-USER-04). */
@Schema(name = "InternalAccountActivityResponse")
public record AccountActivityResponse(

		@Schema(example = "01JBQ9YDX7K3M8N5P2R4T6V8CC") String id,

		@Schema(example = "SUSPENDED") AccountActivityType action,

		@Schema(example = "01JBQ9YDX7K3M8N5P2R4T6V8AA",
				description = "userId của Admin thực hiện; null = chính người dùng hoặc hệ thống")
		String actorId,

		@Schema(example = "admin.vmarket", description = "username của Admin thực hiện (null nếu không có)")
		String actorUsername,

		@Schema(example = "Spam đánh giá sản phẩm nhiều lần", description = "Lý do khoá (chỉ có ở SUSPENDED)")
		String reason,

		Instant createdAt) {

	public static AccountActivityResponse from(AccountActivity a, String actorUsername) {
		return new AccountActivityResponse(a.getId(), a.getAction(), a.getActorId(), actorUsername, a.getReason(),
				a.getCreatedAt());
	}
}
