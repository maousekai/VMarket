package com.vmarket.auth.dto.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Đổi mật khẩu khi đã đăng nhập (FR-USER-03) — user-service gọi thay người dùng.
 *
 * <p>Chính sách mật khẩu mới lặp lại đúng regex của {@code RegisterRequest}: auth-service
 * là chủ sở hữu mật khẩu nên tự kiểm tra, không tin phần kiểm tra ở service gọi vào.
 */
@Schema(name = "InternalChangePasswordRequest")
public record ChangePasswordRequest(

		@Schema(example = "Abcd1234@")
		@NotBlank(message = "Mật khẩu hiện tại không được để trống")
		@Size(max = 72, message = "Mật khẩu hiện tại không hợp lệ")
		String currentPassword,

		@Schema(example = "Xyz98765#", description = "8–32 ký tự, ít nhất 1 chữ hoa, 1 số và 1 ký tự đặc biệt")
		@NotBlank(message = "Mật khẩu mới không được để trống")
		@Pattern(regexp = "^(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,32}$",
				message = "Mật khẩu phải 8–32 ký tự, có ít nhất 1 chữ hoa, 1 số và 1 ký tự đặc biệt")
		String newPassword) {
}
