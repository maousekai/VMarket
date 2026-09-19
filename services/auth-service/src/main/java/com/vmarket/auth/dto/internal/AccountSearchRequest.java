package com.vmarket.auth.dto.internal;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Tìm kiếm tài khoản cho Admin (FR-USER-04).
 *
 * <p>Là {@code POST} chứ không phải {@code GET}: {@code userIds} có thể tới vài trăm
 * ULID, vượt độ dài URL an toàn.
 *
 * <p>Điều kiện: {@code status} (nếu có) <b>VÀ</b> ({@code q} khớp email/username
 * <b>HOẶC</b> id nằm trong {@code userIds}). {@code userIds} là các tài khoản mà
 * user-service tìm thấy theo họ tên / số điện thoại — dữ liệu auth-service không có —
 * nhờ vậy một ô tìm kiếm phủ được cả hai CSDL mà phân trang vẫn đúng.
 */
@Schema(name = "InternalAccountSearchRequest")
public record AccountSearchRequest(

		@Schema(example = "an.nguyen", description = "Từ khoá khớp một phần email hoặc username")
		@Size(max = 100, message = "Từ khoá tối đa 100 ký tự")
		String q,

		@Schema(description = "Lọc theo trạng thái khoá bởi Admin; bỏ trống = tất cả")
		AccountFilterStatus status,

		@Schema(description = "Id tài khoản khớp thêm (từ user-service); dùng cùng q theo phép HOẶC")
		@Size(max = AccountSearchRequest.MAX_USER_IDS, message = "Tối đa " + AccountSearchRequest.MAX_USER_IDS + " userId")
		List<String> userIds,

		@Schema(example = "0")
		@Min(value = 0, message = "page phải >= 0")
		int page,

		@Schema(example = "20")
		@Min(value = 1, message = "size phải >= 1")
		@Max(value = 100, message = "size tối đa 100")
		int size) {

	public static final int MAX_USER_IDS = 500;

	public enum AccountFilterStatus {
		/** Không bị Admin khoá (có thể đang khoá tạm do đăng nhập sai). */
		ACTIVE,
		/** Đang bị Admin khoá. */
		SUSPENDED
	}
}
