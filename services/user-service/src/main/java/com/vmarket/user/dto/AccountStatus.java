package com.vmarket.user.dto;

/**
 * Trạng thái tài khoản dưới góc nhìn Admin (FR-USER-04).
 *
 * <p>Chỉ phản ánh khoá <b>bởi Admin</b>. Khoá tạm 15 phút do đăng nhập sai (FR-AUTH-02)
 * không đổi trạng thái — xem trường {@code loginLockedUntil} của {@link AdminUserResponse}.
 */
public enum AccountStatus {
	ACTIVE,
	LOCKED;

	/** Giá trị lọc tương ứng bên API nội bộ của auth-service. */
	public String toAuthFilter() {
		return this == LOCKED ? "SUSPENDED" : "ACTIVE";
	}
}
