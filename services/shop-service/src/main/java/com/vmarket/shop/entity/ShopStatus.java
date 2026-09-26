package com.vmarket.shop.entity;

/**
 * Trạng thái vòng đời gian hàng (UC-10, UC-15). Các bước chuyển hợp lệ giữa các trạng
 * thái nằm ở {@link ShopAction}.
 */
public enum ShopStatus {

	/** Hồ sơ vừa nộp hoặc vừa gửi lại sau khi bị từ chối. */
	PENDING("Chờ duyệt"),

	/** Hiện với người mua, người bán được bán hàng. */
	ACTIVE("Hoạt động"),

	/** Người bán sửa hồ sơ rồi gửi lại. */
	REJECTED("Bị từ chối"),

	/** Ẩn khỏi người mua (kèm toàn bộ sản phẩm — UC-15). */
	SUSPENDED("Bị đình chỉ");

	private final String label;

	ShopStatus(String label) {
		this.label = label;
	}

	/** Tên tiếng Việt, dùng trong thông báo lỗi cho người dùng. */
	public String label() {
		return label;
	}

	/**
	 * Người bán có được sửa thông tin gian hàng ở trạng thái này không.
	 *
	 * <p>Chặn khi đang bị đình chỉ: sửa được thì người bán có thể "tẩy" nội dung vi
	 * phạm trong lúc Admin còn xem xét, và hồ sơ Admin nhìn thấy không còn là hồ sơ đã
	 * bị đình chỉ.
	 */
	public boolean isEditableByOwner() {
		return this != SUSPENDED;
	}
}
