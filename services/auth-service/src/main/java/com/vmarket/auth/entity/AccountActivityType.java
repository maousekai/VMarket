package com.vmarket.auth.entity;

/**
 * Loại sự kiện trong lịch sử hoạt động cơ bản của tài khoản (FR-USER-04). Chỉ gồm
 * các sự kiện làm <b>đổi trạng thái</b> tài khoản — đăng nhập thành công không ghi ở
 * đây (quá dày, không giúp Admin xử lý vi phạm; thuộc về log / audit hạ tầng).
 */
public enum AccountActivityType {
	/** Admin khoá tài khoản ({@code actorId} = Admin, có {@code reason}). */
	SUSPENDED,
	/** Admin mở khoá ({@code actorId} = Admin). */
	UNSUSPENDED,
	/** Hệ thống khoá tạm 15 phút do nhập sai mật khẩu 5 lần (FR-AUTH-02). */
	LOGIN_LOCKED,
	/** Người dùng tự đổi mật khẩu khi đã đăng nhập (FR-USER-03). */
	PASSWORD_CHANGED,
	/** Đặt lại mật khẩu bằng mã gửi qua email (FR-AUTH-04). */
	PASSWORD_RESET
}
