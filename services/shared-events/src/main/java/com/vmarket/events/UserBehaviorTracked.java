package com.vmarket.events;

/**
 * Hành vi người dùng cho Recommendation Service (FR-REC-03).
 *
 * <p>Producer theo từng hành vi:
 * <ul>
 *   <li>{@code view} — Recommendation Service, qua {@code POST /api/ai/recommendations/events}
 *       với userId lấy từ JWT (browser không nói chuyện trực tiếp với broker).</li>
 *   <li>{@code add_to_cart} — Cart Service sau khi thêm sản phẩm vào giỏ.</li>
 *   <li>{@code purchase} — Order Service khi đơn chuyển sang {@code DELIVERED}.</li>
 * </ul>
 * Payload phải khớp đúng ba trường này: consumer từ chối trường lạ.
 */
public record UserBehaviorTracked(String userId, String productId, String action) {

	public static final String VIEW = "view";
	public static final String ADD_TO_CART = "add_to_cart";
	public static final String PURCHASE = "purchase";
}
