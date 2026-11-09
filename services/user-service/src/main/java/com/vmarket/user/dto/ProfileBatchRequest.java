package com.vmarket.user.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/** Yêu cầu lấy tóm tắt hồ sơ theo lô — dùng cho shop-service enrich tên/avatar. */
public record ProfileBatchRequest(
		@NotEmpty
		@Size(max = 100, message = "Tối đa 100 userId mỗi lần")
		List<@NotBlank @Size(max = 26) String> userIds) {
}
