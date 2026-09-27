package com.vmarket.user.dto;

import java.time.Instant;
import java.util.List;

import com.vmarket.user.client.AuthAccount;
import com.vmarket.user.entity.UserProfile;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Một người dùng trong màn hình quản trị (FR-USER-04): tài khoản (auth-service) ghép
 * với hồ sơ (user-service).
 *
 * <p>Các trường hồ sơ ({@code fullName}, {@code phone}, {@code avatarUrl}) là
 * {@code null} khi người dùng chưa từng mở trang hồ sơ — tài khoản vẫn hiện đầy đủ
 * vì danh sách lấy từ auth-service, nơi có mọi tài khoản.
 */
@Schema(name = "AdminUserResponse", description = "Người dùng dưới góc nhìn Admin")
public record AdminUserResponse(

		@Schema(example = "01JBQ9YDX7K3M8N5P2R4T6V8W0") String userId,

		@Schema(example = "an.nguyen@example.com") String email,

		@Schema(example = "an.nguyen") String username,

		@Schema(example = "[\"BUYER\"]") List<String> roles,

		@Schema(description = "Đã xác thực email chưa") boolean emailVerified,

		@Schema(example = "ACTIVE", description = "LOCKED = bị Admin khoá") AccountStatus status,

		@Schema(description = "Thời điểm Admin khoá (null nếu ACTIVE)") Instant lockedAt,

		@Schema(example = "Spam đánh giá sản phẩm nhiều lần") String lockReason,

		@Schema(description = "userId của Admin đã khoá") String lockedBy,

		@Schema(description = "Khoá tạm do đăng nhập sai 5 lần, tới thời điểm này (null = không khoá tạm). "
				+ "Mở khoá cũng gỡ khoá tạm này.")
		Instant loginLockedUntil,

		@Schema(example = "Nguyễn Văn An") String fullName,

		@Schema(example = "0912345678") String phone,

		@Schema(example = "https://cdn.vmarket.vn/avatars/an.jpg") String avatarUrl,

		@Schema(description = "Thời điểm tạo tài khoản") Instant createdAt) {

	/** {@code profile} có thể {@code null} (chưa có hồ sơ). */
	public static AdminUserResponse of(AuthAccount account, UserProfile profile) {
		return new AdminUserResponse(
				account.userId(),
				account.email(),
				account.username(),
				account.roles() == null ? List.of() : account.roles(),
				account.emailVerified(),
				account.suspended() ? AccountStatus.LOCKED : AccountStatus.ACTIVE,
				account.suspendedAt(),
				account.suspendedReason(),
				account.suspendedBy(),
				account.lockedUntil(),
				profile == null ? null : profile.getFullName(),
				profile == null ? null : profile.getPhone(),
				profile == null ? null : profile.getAvatarUrl(),
				account.createdAt());
	}
}
