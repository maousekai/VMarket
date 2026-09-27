package com.vmarket.user.client;

import java.util.List;

/** Một trang lịch sử tài khoản từ auth-service ({@code InternalAccountActivityPageResponse}). */
public record AuthAccountActivityPage(
		List<AuthAccountActivity> items,
		int page,
		int size,
		long totalElements,
		int totalPages) {
}
