package com.vmarket.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Đổi mật khẩu khi đã đăng nhập (FR-USER-03).
 *
 * <p>Chính sách mật khẩu mới trùng hệt đăng ký ({@code RegisterRequest} của auth-service).
 * Kiểm tra ở đây để trả lỗi sớm, có {@code details} theo trường; auth-service vẫn tự
 * kiểm tra lại vì là chủ sở hữu mật khẩu.
 *
 * <p>Không có trường "nhập lại mật khẩu mới": so khớp hai ô là việc của form phía
 * client, server nhận hai chuỗi giống nhau thì không có thêm thông tin gì.
 */
@Schema(name = "ChangePasswordRequest")
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
