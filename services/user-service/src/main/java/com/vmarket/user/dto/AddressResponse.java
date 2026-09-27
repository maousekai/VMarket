package com.vmarket.user.dto;

import java.time.Instant;

import com.vmarket.user.entity.Address;

import io.swagger.v3.oas.annotations.media.Schema;

/** Một địa chỉ trong sổ địa chỉ (FR-USER-02). */
@Schema(name = "AddressResponse", description = "Địa chỉ giao hàng")
public record AddressResponse(

		@Schema(example = "01JBQ9YDX7K3M8N5P2R4T6V8W0") String id,

		@Schema(example = "Nguyễn Văn An") String recipientName,

		@Schema(example = "0912345678") String phone,

		@Schema(example = "Đà Nẵng") String province,

		@Schema(example = "Hải Châu") String district,

		@Schema(example = "Thạch Thang") String ward,

		@Schema(example = "54 Nguyễn Lương Bằng") String streetAddress,

		@Schema(example = "Gọi trước khi giao") String note,

		@Schema(example = "true", description = "Địa chỉ mặc định khi đặt hàng")
		boolean isDefault,

		Instant createdAt,

		Instant updatedAt) {

	public static AddressResponse from(Address a) {
		return new AddressResponse(
				a.getId(),
				a.getRecipientName(),
				a.getPhone(),
				a.getProvince(),
				a.getDistrict(),
				a.getWard(),
				a.getStreetAddress(),
				a.getNote(),
				a.isDefault(),
				a.getCreatedAt(),
				a.getUpdatedAt());
	}
}
