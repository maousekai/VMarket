package com.vmarket.shop.dto;

import java.time.Instant;

import com.vmarket.shop.entity.ShopStatus;
import com.vmarket.shop.entity.ShopStatusHistory;

import io.swagger.v3.oas.annotations.media.Schema;

/** Một bước chuyển trạng thái của gian hàng (ShopStatusHistory). */
@Schema(name = "ShopStatusHistoryResponse", description = "Một bước chuyển trạng thái gian hàng")
public record ShopStatusHistoryResponse(

		@Schema(example = "PENDING", description = "null ở bước đầu tiên (hồ sơ vừa nộp)") ShopStatus fromStatus,

		@Schema(example = "REJECTED") ShopStatus toStatus,

		@Schema(example = "Ảnh logo không rõ nét") String reason,

		@Schema(example = "01JBQ9YDX7K3M8N5P2R4T6V8W9", description = "userId người thao tác") String changedBy,

		Instant createdAt) {

	public static ShopStatusHistoryResponse from(ShopStatusHistory h) {
		return new ShopStatusHistoryResponse(h.getFromStatus(), h.getToStatus(), h.getReason(), h.getChangedBy(),
				h.getCreatedAt());
	}
}
