package com.vmarket.shop.dto;

import java.time.Instant;

import com.vmarket.shop.entity.Shop;
import com.vmarket.shop.entity.ShopStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Hồ sơ gian hàng đầy đủ — cho chủ gian hàng ({@code /me}) và Admin. Khác
 * {@link PublicShopResponse} ở chỗ có thông tin liên hệ, địa chỉ lấy hàng chi tiết,
 * trạng thái kiểm duyệt và lý do từ chối / đình chỉ.
 */
@Schema(name = "ShopResponse", description = "Hồ sơ gian hàng (chủ gian hàng / Admin)")
public record ShopResponse(

		@Schema(example = "01JBQ9YDX7K3M8N5P2R4T6V8W0") String id,

		@Schema(example = "01JBQ9YDX7K3M8N5P2R4T6V8W1", description = "userId của chủ gian hàng") String ownerId,

		@Schema(example = "Tiệm Gốm Hội An") String name,

		String description,

		String logoUrl,

		String coverUrl,

		String policies,

		@Schema(example = "lienhe@gomhoian.vn") String contactEmail,

		@Schema(example = "0912345678") String contactPhone,

		@Schema(example = "Quảng Nam") String province,

		@Schema(example = "Hội An") String district,

		@Schema(example = "Thanh Hà") String ward,

		@Schema(example = "12 Phạm Phán") String streetAddress,

		@Schema(example = "PENDING") ShopStatus status,

		@Schema(example = "Ảnh logo không rõ nét",
				description = "Lý do của lần từ chối / đình chỉ gần nhất; null khi đang chờ duyệt hoặc hoạt động")
		String statusReason,

		@Schema(description = "Lần đầu được duyệt; null nếu chưa từng được duyệt") Instant approvedAt,

		Instant createdAt,

		Instant updatedAt) {

	public static ShopResponse from(Shop s) {
		return new ShopResponse(
				s.getId(),
				s.getOwnerId(),
				s.getName(),
				s.getDescription(),
				s.getLogoUrl(),
				s.getCoverUrl(),
				s.getPolicies(),
				s.getContactEmail(),
				s.getContactPhone(),
				s.getProvince(),
				s.getDistrict(),
				s.getWard(),
				s.getStreetAddress(),
				s.getStatus(),
				s.getStatusReason(),
				s.getApprovedAt(),
				s.getCreatedAt(),
				s.getUpdatedAt());
	}
}
