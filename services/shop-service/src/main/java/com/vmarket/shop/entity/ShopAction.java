package com.vmarket.shop.entity;

/**
 * Các bước chuyển trạng thái hợp lệ của gian hàng — bảng chuyển trạng thái DUY NHẤT
 * của service (UC-10, UC-15, FR-SHOP-04).
 *
 * <pre>
 *   (mới) ──nộp hồ sơ──► PENDING ────APPROVE────► ACTIVE
 *                         ▲    │                   │   ▲
 *                RESUBMIT │    │ REJECT    SUSPEND │   │ REINSTATE
 *                         │    ▼ (lý do)   (lý do) ▼   │
 *                        REJECTED                SUSPENDED
 * </pre>
 *
 * <p>Mỗi hành động gắn với <b>đúng một</b> trạng thái nguồn. {@code APPROVE} và
 * {@code REINSTATE} cùng đưa về {@code ACTIVE} nhưng không thay thế nhau được: "duyệt"
 * một gian hàng đang bị đình chỉ bị từ chối, Admin phải chủ động "gỡ đình chỉ" — hai
 * quyết định khác nhau, lịch sử và sự kiện phát ra ({@code reinstated}) cũng khác.
 *
 * <p>Bước "nộp hồ sơ" lần đầu không có ở đây vì không có trạng thái nguồn.
 */
public enum ShopAction {

	/** Người bán gửi lại hồ sơ đã sửa sau khi bị từ chối. */
	RESUBMIT(ShopStatus.REJECTED, ShopStatus.PENDING, "gửi lại hồ sơ"),

	/** Admin duyệt hồ sơ → phát {@code ShopApproved}. */
	APPROVE(ShopStatus.PENDING, ShopStatus.ACTIVE, "duyệt"),

	/** Admin từ chối hồ sơ (bắt buộc kèm lý do). */
	REJECT(ShopStatus.PENDING, ShopStatus.REJECTED, "từ chối"),

	/** Admin đình chỉ gian hàng đang hoạt động (bắt buộc kèm lý do) → phát {@code ShopSuspended}. */
	SUSPEND(ShopStatus.ACTIVE, ShopStatus.SUSPENDED, "đình chỉ"),

	/** Admin gỡ đình chỉ → phát {@code ShopApproved} với {@code reinstated = true}. */
	REINSTATE(ShopStatus.SUSPENDED, ShopStatus.ACTIVE, "gỡ đình chỉ");

	private final ShopStatus from;
	private final ShopStatus to;
	private final String label;

	ShopAction(ShopStatus from, ShopStatus to, String label) {
		this.from = from;
		this.to = to;
		this.label = label;
	}

	public ShopStatus from() {
		return from;
	}

	public ShopStatus to() {
		return to;
	}

	/** Động từ tiếng Việt, dùng trong thông báo lỗi. */
	public String label() {
		return label;
	}

	public boolean isAllowedFrom(ShopStatus current) {
		return from == current;
	}
}
