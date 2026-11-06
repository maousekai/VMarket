package com.vmarket.auth.dto.internal;

import com.vmarket.auth.entity.User;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Thông tin liên hệ tối thiểu của một tài khoản cho notification-service (PBL6-23)
 * gửi email giao dịch. Không trả vai trò, lịch sử khoá hay dữ liệu nhạy cảm khác.
 */
@Schema(name = "InternalUserContactResponse")
public record UserContactResponse(

		@Schema(example = "01JBQ9YDX7K3M8N5P2R4T6V8BB") String userId,

		@Schema(example = "an.nguyen@example.com") String email,

		@Schema(description = "Email đã xác thực OTP") boolean emailVerified,

		@Schema(description = "Đang bị Admin khoá") boolean suspended) {

	public static UserContactResponse from(User u) {
		return new UserContactResponse(u.getId(), u.getEmail(), u.isEmailVerified(), u.isSuspended());
	}
}
