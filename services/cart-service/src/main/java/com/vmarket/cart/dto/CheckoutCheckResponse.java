package com.vmarket.cart.dto;

import java.util.List;

/**
 * Kết quả kiểm tra giỏ hàng trước thanh toán (FR-CART-03).
 *
 * @param ok     {@code true} khi có thể thanh toán an toàn (không có issue)
 * @param issues danh sách vấn đề; rỗng khi {@code ok=true}
 */
public record CheckoutCheckResponse(
		boolean ok,
		List<CheckoutIssueDto> issues) {

	public static CheckoutCheckResponse withIssues(List<CheckoutIssueDto> issues) {
		return new CheckoutCheckResponse(issues.isEmpty(), List.copyOf(issues));
	}
}
