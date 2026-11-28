package com.vmarket.order.entity;

/**
 * Vòng đời của một đơn hàng (FR-ORDER-02 — theo dõi trạng thái).
 *
 * <p>Chuyển trạng thái hợp lệ:
 * <pre>
 *   PENDING ──cancel──> CANCELLED
 *      │ (seller xác nhận — ticket sau)
 *      ▼
 *   PROCESSING ──> SHIPPED ──> DELIVERED
 * </pre>
 *
 * <p>Với ticket này chỉ có hai chuyển đổi được cưỡng chế ở tầng service:
 * tạo đơn → {@code PENDING}, và {@code PENDING → CANCELLED} do người mua tự huỷ
 * (FR-ORDER-03). Các trạng thái còn lại do luồng phía seller/giao hàng cập nhật
 * (ticket khác) — chúng có mặt ở đây từ đầu để khách thấy tiến độ đơn cũ, và để
 * enum không phải thêm giá trị làm lệch độ dài cột {@code status VARCHAR(20)}
 * sau khi đã có dữ liệu.
 */
public enum OrderStatus {

	/** Vừa tạo, chờ người bán xác nhận. Đây là trạng thái duy nhất được huỷ. */
	PENDING,
	WAITING_PAYMENT,

	/** Người bán đã xác nhận, đang chuẩn bị hàng. */
	PROCESSING,

	/** Đã bàn giao đơn vị vận chuyển. */
	SHIPPED,

	/** Người mua đã nhận hàng. */
	DELIVERED,

	/** Đơn đã hoàn tất sau 7 ngày hoặc người mua xác nhận hoàn tất (FR-ORD-02). */
	COMPLETED,

	/** Người mua hoặc hệ thống huỷ đơn. */
	CANCELLED,

	/**
	 * Giao không thành công, hàng đang hoàn về shop (chuyến giao RETURNING / RETURN_DROPPED_OFF).
	 * Review PR 68 lần 2, mục 4.7.
	 */
	DELIVERY_FAILED,

	/**
	 * Hàng đã hoàn trả cho shop (chuyến giao RETURNED) - thay cho CANCELLED. Đơn trả trước được
	 * payment-service hoàn tiền tự động.
	 */
	RETURNED_TO_SELLER
}
