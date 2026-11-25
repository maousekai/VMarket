package com.vmarket.order.entity;

/**
 * Trạng thái tiền của đơn, độc lập với {@link OrderStatus} (PBL6-63).
 *
 * <pre>
 *   UNPAID ──VNPAY PaymentSucceeded / COD giao thành công──> PAID ──huỷ đơn đã thu tiền──> REFUNDED
 * </pre>
 *
 * <p>Chỉ đi một chiều: không thể thanh toán hai lần (PAID → PAID) hay hoàn tiền khi chưa thu /
 * hoàn hai lần (UNPAID → REFUNDED, REFUNDED → REFUNDED).
 */
public enum PaymentStatus {
	UNPAID,
	PAID,
	REFUNDED;

	public boolean canTransitionTo(PaymentStatus next) {
		return (this == UNPAID && next == PAID) || (this == PAID && next == REFUNDED);
	}
}
