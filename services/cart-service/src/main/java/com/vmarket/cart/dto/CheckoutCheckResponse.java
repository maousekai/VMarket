package com.vmarket.cart.dto;

import java.util.List;

/**
 * Kết quả kiểm tra giỏ hàng trước thanh toán (FR-CART-03).
 *
 * @param ok     {@code true} khi có thể thanh toán an toàn (không có issue)
 * @param issues danh sách vấn đề; rỗng khi {@code ok=true}
 * @param items  các dòng hàng đúng như lần kiểm tra này đã đọc (luôn có, kể cả khi
 *               {@code ok=false}; giỏ rỗng → {@code []}). Trường cộng thêm cho order-service
 *               dựng đơn từ đúng thứ đã được kiểm tra.
 */
public record CheckoutCheckResponse(
		boolean ok,
		List<CheckoutIssueDto> issues,
		List<CheckoutItemDto> items) {

	public static CheckoutCheckResponse withIssues(List<CheckoutIssueDto> issues,
			List<CheckoutItemDto> items) {
		return new CheckoutCheckResponse(issues.isEmpty(), List.copyOf(issues), List.copyOf(items));
	}
}
