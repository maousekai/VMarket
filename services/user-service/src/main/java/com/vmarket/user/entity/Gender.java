package com.vmarket.user.entity;

/**
 * Giới tính trong hồ sơ (FR-USER-01). Lưu dạng chuỗi ({@code EnumType.STRING})
 * chứ không phải ordinal: chèn thêm giá trị mới về sau sẽ không làm sai lệch dữ
 * liệu cũ đã lưu.
 */
public enum Gender {
	MALE,
	FEMALE,
	OTHER,
	/** Người dùng không muốn tiết lộ. Khác với {@code null} = chưa từng khai. */
	UNDISCLOSED
}
