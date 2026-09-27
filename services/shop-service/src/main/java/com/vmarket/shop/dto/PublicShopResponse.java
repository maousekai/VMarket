package com.vmarket.shop.dto;

import java.time.Instant;

import com.vmarket.shop.entity.Shop;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Trang gian hàng công khai (FR-SHOP-03) — ai cũng xem được, kể cả khách.
 *
 * <p>Cố ý <b>không</b> có: {@code ownerId}, email / số điện thoại liên hệ, địa chỉ kho
 * chi tiết, trạng thái kiểm duyệt. Đó là dữ liệu dành cho nền tảng (Admin, shipper lấy
 * hàng), không phải cho người mua; chỉ để lại tỉnh/thành làm "khu vực" của gian hàng.
 *
 * <p>Danh sách sản phẩm và điểm đánh giá trung bình của trang gian hàng thuộc dữ liệu
 * của Product Catalog và Review Service (database-per-service) — frontend ghép bằng
 * {@code GET /api/products?shopId=...} và API tổng hợp đánh giá của Review Service.
 */
@Schema(name = "PublicShopResponse", description = "Trang gian hàng công khai")
public record PublicShopResponse(

		@Schema(example = "01JBQ9YDX7K3M8N5P2R4T6V8W0") String id,

		@Schema(example = "Tiệm Gốm Hội An") String name,

		String description,

		String logoUrl,

		String coverUrl,

		String policies,

		@Schema(example = "Quảng Nam", description = "Khu vực của gian hàng") String province,

		@Schema(description = "Hoạt động trên VMarket từ (lần đầu được duyệt)") Instant approvedAt) {

	public static PublicShopResponse from(Shop s) {
		return new PublicShopResponse(
				s.getId(),
				s.getName(),
				s.getDescription(),
				s.getLogoUrl(),
				s.getCoverUrl(),
				s.getPolicies(),
				s.getProvince(),
				s.getApprovedAt());
	}
}
