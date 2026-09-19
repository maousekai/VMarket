package com.vmarket.auth.dto.internal;

import java.time.Instant;
import java.util.List;

import com.vmarket.auth.entity.User;

import io.swagger.v3.oas.annotations.media.Schema;

/** Thông tin tài khoản cho service nội bộ (Admin quản lý người dùng — FR-USER-04). */
@Schema(name = "InternalAccountResponse")
public record AccountResponse(

		@Schema(example = "01JBQ9YDX7K3M8N5P2R4T6V8BB") String userId,

		@Schema(example = "an.nguyen@example.com") String email,

		@Schema(example = "an.nguyen") String username,

		@Schema(example = "[\"BUYER\"]") List<String> roles,

		boolean emailVerified,

		@Schema(description = "Đang bị Admin khoá") boolean suspended,

		Instant suspendedAt,

		String suspendedReason,

		@Schema(description = "userId của Admin đã khoá") String suspendedBy,

		@Schema(description = "Khoá tạm do đăng nhập sai tới thời điểm này (null = không khoá tạm)")
		Instant lockedUntil,

		Instant createdAt) {

	public static AccountResponse from(User u, List<String> roles, Instant now) {
		Instant lockedUntil = u.getLockedUntil() != null && u.getLockedUntil().isAfter(now) ? u.getLockedUntil() : null;
		return new AccountResponse(
				u.getId(),
				u.getEmail(),
				u.getUsername(),
				roles,
				u.isEmailVerified(),
				u.isSuspended(),
				u.getSuspendedAt(),
				u.getSuspendedReason(),
				u.getSuspendedBy(),
				lockedUntil,
				u.getCreatedAt());
	}
}
