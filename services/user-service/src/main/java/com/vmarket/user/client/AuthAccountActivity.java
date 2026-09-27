package com.vmarket.user.client;

import java.time.Instant;

/**
 * Một sự kiện lịch sử tài khoản như auth-service trả về ({@code InternalAccountActivityResponse}).
 *
 * @param action {@code SUSPENDED} | {@code UNSUSPENDED} | {@code LOGIN_LOCKED} |
 *               {@code PASSWORD_CHANGED} | {@code PASSWORD_RESET}
 */
public record AuthAccountActivity(
		String id,
		String action,
		String actorId,
		String actorUsername,
		String reason,
		Instant createdAt) {
}
