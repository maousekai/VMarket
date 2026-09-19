package com.vmarket.auth.dto.internal;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** Một trang kết quả tìm kiếm tài khoản. Không trả thẳng {@code Page} của Spring Data (hình dạng JSON không ổn định). */
@Schema(name = "InternalAccountPageResponse")
public record AccountPageResponse(
		List<AccountResponse> items,
		int page,
		int size,
		long totalElements,
		int totalPages) {
}
