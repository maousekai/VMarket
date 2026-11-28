package com.vmarket.order.entity;

/**
 * Trạng thái tiền của đơn, độc lập với {@link OrderStatus} (PBL6-63).
 *
 * <pre>
 *   UNPAID ──VNPAY PaymentSucceeded / COD giao thành công──> PAID ──huỷ đơn đã thu tiền──> REFUND_PENDING
 * </pre>
 *
 * <p>Với COD, order-service suy ra PAID khi giao hàng thành công. Payment-service xác nhận
 * thu tiền COD và kết quả hoàn tiền.
 */
public enum PaymentStatus {
	UNPAID,
	PAID,
	REFUND_PENDING;

	public boolean canTransitionTo(PaymentStatus next) {
		return (this == UNPAID && next == PAID) || (this == PAID && next == REFUND_PENDING);
	}
}
