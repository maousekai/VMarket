package com.vmarket.user.client;

import java.util.List;

/**
 * Điều kiện tìm tài khoản gửi sang auth-service ({@code InternalAccountSearchRequest}):
 * {@code status} VÀ ({@code q} khớp email/username HOẶC id thuộc {@code userIds}).
 *
 * @param status {@code ACTIVE} | {@code SUSPENDED} | {@code null} (tất cả)
 */
public record AuthAccountSearch(
		String q,
		String status,
		List<String> userIds,
		int page,
		int size) {

	/** Trần số userId auth-service nhận trong một lần tìm — khớp {@code AccountSearchRequest.MAX_USER_IDS}. */
	public static final int MAX_USER_IDS = 500;
}
