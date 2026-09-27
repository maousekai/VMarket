package com.vmarket.auth.dto.internal;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** Một trang lịch sử hoạt động của tài khoản, mới nhất trước. */
@Schema(name = "InternalAccountActivityPageResponse")
public record AccountActivityPageResponse(
		List<AccountActivityResponse> items,
		int page,
		int size,
		long totalElements,
		int totalPages) {
}
