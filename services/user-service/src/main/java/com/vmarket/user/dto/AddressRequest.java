package com.vmarket.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Thêm mới hoặc sửa một địa chỉ (FR-USER-02). Dùng chung cho {@code POST} và
 * {@code PUT} vì cả hai đều mô tả trạng thái đầy đủ của địa chỉ — tách thành hai
 * record giống hệt nhau chỉ tạo thêm chỗ để hai bên lệch nhau về sau.
 *
 * <p>Cờ mặc định <b>không</b> nằm trong body: đặt mặc định là một hành động riêng
 * ({@code PUT /api/users/me/addresses/{id}/default}) vì nó ảnh hưởng tới các địa chỉ
 * KHÁC của cùng người dùng, không chỉ địa chỉ đang sửa. Nhét nó vào đây sẽ khiến một
 * request "sửa số nhà" âm thầm gỡ mặc định của địa chỉ khác.
 *
 * <p>Ngoại lệ: địa chỉ ĐẦU TIÊN của một người tự động thành mặc định — xử lý ở
 * {@code AddressService}, client không cần gửi gì thêm.
 */
@Schema(name = "AddressRequest", description = "Dữ liệu địa chỉ giao hàng")
public record AddressRequest(

		@Schema(example = "Nguyễn Văn An", description = "Có thể khác chủ tài khoản")
		@NotBlank(message = "Tên người nhận không được để trống")
		@Size(max = 100, message = "Tên người nhận tối đa 100 ký tự")
		String recipientName,

		@Schema(example = "0912345678")
		@NotBlank(message = "Số điện thoại không được để trống")
		@Pattern(regexp = "^(0\\d{9}|\\+84\\d{9})$",
				message = "Số điện thoại phải là số di động Việt Nam hợp lệ")
		String phone,

		@Schema(example = "Đà Nẵng")
		@NotBlank(message = "Tỉnh/thành phố không được để trống")
		@Size(max = 100, message = "Tỉnh/thành phố tối đa 100 ký tự")
		String province,

		@Schema(example = "Hải Châu")
		@NotBlank(message = "Quận/huyện không được để trống")
		@Size(max = 100, message = "Quận/huyện tối đa 100 ký tự")
		String district,

		@Schema(example = "Thạch Thang")
		@NotBlank(message = "Phường/xã không được để trống")
		@Size(max = 100, message = "Phường/xã tối đa 100 ký tự")
		String ward,

		@Schema(example = "54 Nguyễn Lương Bằng", description = "Số nhà, tên đường, toà nhà")
		@NotBlank(message = "Địa chỉ chi tiết không được để trống")
		@Size(max = 255, message = "Địa chỉ chi tiết tối đa 255 ký tự")
		String streetAddress,

		@Schema(example = "Gọi trước khi giao")
		@Size(max = 255, message = "Ghi chú tối đa 255 ký tự")
		String note) {
}
