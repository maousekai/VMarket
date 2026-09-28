package com.vmarket.events;

/**
 * Order Service phát mỗi khi đơn chuyển trạng thái.
 *
 * @param orderId định danh đơn hàng
 * @param previousStatus trạng thái trước chuyển đổi
 * @param status trạng thái mới
 * @param paymentMethod phương thức thanh toán, ví dụ PAYOS hoặc COD
 */
public record OrderStatusChanged(String orderId, String previousStatus, String status, String paymentMethod) {
}
