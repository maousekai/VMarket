package com.vmarket.shop.dto;

import java.time.Instant;

import com.vmarket.shop.entity.ShopProfileChange;
import com.vmarket.shop.entity.ShopStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/** Một trường trong hồ sơ gian hàng vừa bị người bán sửa (nhật ký cho Admin). */
@Schema(name = "ShopProfileChangeResponse", description = "Một lần sửa nội dung hồ sơ gian hàng")
public record ShopProfileChangeResponse(

		@Schema(example = "policies", description = "Tên trường bị đổi") String fieldName,

		@Schema(description = "Giá trị trước khi sửa; null nếu trước đó bỏ trống") String oldValue,

		@Schema(description = "Giá trị sau khi sửa; null nếu người bán xoá trường này") String newValue,

		@Schema(example = "ACTIVE", description = "Trạng thái gian hàng ngay lúc sửa")
		ShopStatus statusAtChange,

		@Schema(example = "01JBQ9YDX7K3M8N5P2R4T6V8W1", description = "userId người sửa (chủ gian hàng)")
		String changedBy,

		Instant createdAt) {

	public static ShopProfileChangeResponse from(ShopProfileChange c) {
		return new ShopProfileChangeResponse(c.getFieldName(), c.getOldValue(), c.getNewValue(),
				c.getStatusAtChange(), c.getChangedBy(), c.getCreatedAt());
	}
}
