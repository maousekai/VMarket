package com.vmarket.user.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.vmarket.user.entity.Gender;
import com.vmarket.user.entity.UserProfile;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Hồ sơ cá nhân trả về cho client (FR-USER-01).
 *
 * <p>Không có email/username: đó là dữ liệu của Identity Service. Client cần thì
 * lấy từ token hoặc gọi auth-service.
 */
@Schema(name = "ProfileResponse", description = "Hồ sơ cá nhân")
public record ProfileResponse(

		@Schema(example = "01JBQ9YDX7K3M8N5P2R4T6V8W0", description = "userId do Identity Service cấp")
		String userId,

		@Schema(example = "Nguyễn Văn An") String fullName,

		@Schema(example = "https://cdn.vmarket.vn/avatars/an.jpg") String avatarUrl,

		@Schema(example = "0912345678") String phone,

		@Schema(example = "2003-05-17") LocalDate dateOfBirth,

		@Schema(example = "MALE") Gender gender,

		Instant createdAt,

		Instant updatedAt) {

	public static ProfileResponse from(UserProfile p) {
		return new ProfileResponse(
				p.getUserId(),
				p.getFullName(),
				p.getAvatarUrl(),
				p.getPhone(),
				p.getDateOfBirth(),
				p.getGender(),
				p.getCreatedAt(),
				p.getUpdatedAt());
	}
}
