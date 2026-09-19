package com.vmarket.user.dto;

import java.time.LocalDate;

import com.vmarket.user.entity.Gender;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Cập nhật hồ sơ cá nhân (FR-USER-01).
 *
 * <p><b>Ngữ nghĩa THAY THẾ TOÀN BỘ, không phải vá từng trường.</b> Đây là {@code PUT}
 * nên body mô tả trạng thái đầy đủ của hồ sơ sau khi cập nhật: trường nào bỏ trống
 * (hoặc gửi {@code null}) sẽ bị <b>xoá</b>, không phải "giữ nguyên giá trị cũ".
 * Client phải gửi lại cả những trường không đổi — form hồ sơ vốn đã có sẵn giá trị
 * hiện tại nên việc này là tự nhiên.
 *
 * <p>Ghi rõ ở đây vì đoán nhầm chỗ này gây lỗi im lặng khó chịu: người dùng sửa mỗi
 * họ tên rồi mất số điện thoại mà không có thông báo lỗi nào.
 */
@Schema(name = "UpdateProfileRequest",
		description = "Trạng thái ĐẦY ĐỦ của hồ sơ sau cập nhật. Trường để trống sẽ bị xoá.")
public record UpdateProfileRequest(

		@Schema(example = "Nguyễn Văn An")
		@Size(max = 100, message = "Họ tên tối đa 100 ký tự")
		String fullName,

		@Schema(example = "https://cdn.vmarket.vn/avatars/an.jpg")
		@Size(max = 500, message = "Đường dẫn ảnh tối đa 500 ký tự")
		@Pattern(regexp = "^$|^https?://.+", message = "Ảnh đại diện phải là URL http hoặc https")
		String avatarUrl,

		@Schema(example = "0912345678", description = "Số di động Việt Nam: 0xxxxxxxxx hoặc +84xxxxxxxxx")
		@Pattern(regexp = "^$|^(0\\d{9}|\\+84\\d{9})$",
				message = "Số điện thoại phải là số di động Việt Nam hợp lệ")
		String phone,

		@Schema(example = "2003-05-17")
		@Past(message = "Ngày sinh phải ở quá khứ")
		LocalDate dateOfBirth,

		@Schema(example = "MALE", description = "MALE, FEMALE, OTHER hoặc UNDISCLOSED")
		Gender gender) {
}
