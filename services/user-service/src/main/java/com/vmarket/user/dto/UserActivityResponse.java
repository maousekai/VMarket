package com.vmarket.user.dto;

import java.time.Instant;

import com.vmarket.user.client.AuthAccountActivity;

import io.swagger.v3.oas.annotations.media.Schema;

/** Một sự kiện trong lịch sử hoạt động cơ bản của người dùng (FR-USER-04). */
@Schema(name = "UserActivityResponse", description = "Sự kiện làm đổi trạng thái tài khoản")
public record UserActivityResponse(

		@Schema(example = "SUSPENDED", description = "SUSPENDED = Admin khoá, UNSUSPENDED = Admin mở khoá, "
				+ "LOGIN_LOCKED = khoá tạm 15 phút do nhập sai mật khẩu 5 lần, PASSWORD_CHANGED = tự đổi mật khẩu, "
				+ "PASSWORD_RESET = đặt lại mật khẩu qua email")
		String action,

		@Schema(example = "01JBQ9YDX7K3M8N5P2R4T6V8AA",
				description = "userId của Admin thực hiện; null = chính người dùng hoặc hệ thống")
		String actorId,

		@Schema(example = "admin.vmarket") String actorUsername,

		@Schema(example = "Spam đánh giá sản phẩm nhiều lần", description = "Lý do khoá (chỉ có ở SUSPENDED)")
		String reason,

		Instant createdAt) {

	public static UserActivityResponse of(AuthAccountActivity a) {
		return new UserActivityResponse(a.action(), a.actorId(), a.actorUsername(), a.reason(), a.createdAt());
	}
}
