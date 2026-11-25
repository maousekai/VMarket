package com.vmarket.order.exception;

import org.springframework.http.HttpStatus;

/** Chuyển trạng thái không có trong bảng của {@code OrderStatus}/{@code PaymentStatus} → 409. */
public class IllegalOrderTransitionException extends ApiException {

	public IllegalOrderTransitionException(String orderId, Enum<?> from, Enum<?> to) {
		super("ILLEGAL_ORDER_TRANSITION", HttpStatus.CONFLICT,
				"Không thể chuyển đơn " + orderId + " từ " + from + " sang " + to);
	}
}
