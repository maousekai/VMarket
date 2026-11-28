package com.vmarket.order.entity;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Vòng đời của một đơn hàng (FR-ORDER-02) và BẢNG CHUYỂN TRẠNG THÁI duy nhất (PBL6-63).
 *
 * <pre>
 *   WAITING_PAYMENT ──paid──> PENDING ──all shops confirm / claim──> PROCESSING
 *        │                       │                                     │
 *        └──fail/expire/cancel───┴──────────> CANCELLED <──────────────┤
 *                                                ▲                     ▼
 *                                                ├──delivery RETURNED── SHIPPED
 *                                                │                     │
 *                                                └──admin── DELIVERED <┘ (PROCESSING ──> DELIVERED
 *                                                              │        khi event giao hàng tới lệch thứ tự)
 *                                                              ▼
 *                                                          COMPLETED
 * </pre>
 *
 * <p>Mọi thay đổi trạng thái phải đi qua {@link Order#transitionTo(OrderStatus)}, nơi kiểm tra
 * {@link #canTransitionTo(OrderStatus)}. Bảng là TRẦN quyền hạn: từng tác nhân (người mua,
 * seller, admin, event) có thể hẹp hơn, nhưng không ai đi được cạnh không có trong bảng.
 *
 * <p>Trạng thái thanh toán (PAID/REFUND_PENDING) là trục riêng — xem {@link PaymentStatus}.
 */
public enum OrderStatus {

	/** Vừa tạo (COD) hoặc đã thanh toán online, chờ người bán xác nhận. */
	PENDING,
	/** Đơn VNPAY chờ thanh toán. */
	WAITING_PAYMENT,

	/** Mọi gian hàng đã xác nhận, đang chuẩn bị hàng / đã bàn giao cho giao hàng. */
	PROCESSING,

	/** Đã bàn giao đơn vị vận chuyển. */
	SHIPPED,

	/** Người mua đã nhận hàng. */
	DELIVERED,

	/** Đơn đã hoàn tất sau 7 ngày hoặc người mua xác nhận hoàn tất (FR-ORD-02). */
	COMPLETED,

	/** Người mua, admin hoặc hệ thống huỷ đơn. */
	CANCELLED;

	private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = Map.of(
			WAITING_PAYMENT, EnumSet.of(PENDING, CANCELLED),
			PENDING, EnumSet.of(PROCESSING, CANCELLED),
			PROCESSING, EnumSet.of(SHIPPED, DELIVERED, CANCELLED),
			SHIPPED, EnumSet.of(DELIVERED, CANCELLED),
			DELIVERED, EnumSet.of(COMPLETED, CANCELLED),
			COMPLETED, EnumSet.noneOf(OrderStatus.class),
			CANCELLED, EnumSet.noneOf(OrderStatus.class));

	public boolean canTransitionTo(OrderStatus next) {
		return next != null && TRANSITIONS.get(this).contains(next);
	}

	public boolean isTerminal() {
		return TRANSITIONS.get(this).isEmpty();
	}
}
