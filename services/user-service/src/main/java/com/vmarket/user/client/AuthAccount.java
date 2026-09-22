package com.vmarket.user.client;

import java.time.Instant;
import java.util.List;

/**
 * Tài khoản như auth-service trả về qua API nội bộ ({@code InternalAccountResponse}).
 * Chỉ dùng trong user-service — không trả thẳng ra client, xem {@code AdminUserResponse}.
 */
public record AuthAccount(
		String userId,
		String email,
		String username,
		List<String> roles,
		boolean emailVerified,
		boolean suspended,
		Instant suspendedAt,
		String suspendedReason,
		String suspendedBy,
		Instant lockedUntil,
		Instant createdAt) {
}
