package com.vmarket.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Lý do Admin từ chối / đình chỉ gian hàng (FR-SHOP-04: "từ chối (kèm lý do)"). Người
 * bán thấy lý do này ở {@code GET /api/shops/me} để biết phải sửa gì.
 */
@Schema(name = "ReasonRequest", description = "Lý do từ chối / đình chỉ")
public record ReasonRequest(

		@Schema(example = "Ảnh logo không rõ nét, vui lòng cập nhật ảnh khác.")
		@NotBlank(message = "Lý do không được để trống")
		@Size(max = 500, message = "Lý do tối đa 500 ký tự")
		String reason) {
}
