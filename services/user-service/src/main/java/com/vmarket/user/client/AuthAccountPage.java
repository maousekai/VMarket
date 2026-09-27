package com.vmarket.user.client;

import java.util.List;

/** Một trang tài khoản từ auth-service ({@code InternalAccountPageResponse}). */
public record AuthAccountPage(
		List<AuthAccount> items,
		int page,
		int size,
		long totalElements,
		int totalPages) {
}
