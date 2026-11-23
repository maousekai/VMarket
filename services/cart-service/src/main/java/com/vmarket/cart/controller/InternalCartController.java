package com.vmarket.cart.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.cart.dto.CartResponse;
import com.vmarket.cart.service.CartService;

/**
 * API giỏ hàng cho cuộc gọi NỘI BỘ service-to-service (order-service đọc/xoá giỏ
 * khi đặt hàng, FR-ORDER-01).
 *
 * <p>Chỉ nhận request có {@code X-Internal-Api-Key} đúng kèm {@code X-User-Id}
 * (xem {@code CartAuthenticationFilter}); {@code SecurityConfig} đòi quyền
 * {@code ROLE_INTERNAL} cho {@code /internal/**}, nên access token của người dùng
 * không gọi được các route này. Ngược lại, route công khai {@code /api/cart/**}
 * chỉ nhận access token — khoá nội bộ ở đó bị bỏ qua (PBL6-50). Gateway chặn
 * {@code /internal/**} ({@code internal-deny-paths}).
 */
@RestController
@RequestMapping("/internal/cart")
public class InternalCartController {

	private final CartService cartService;

	public InternalCartController(CartService cartService) {
		this.cartService = cartService;
	}

	/** Đọc giỏ của người dùng trong {@code X-User-Id}. */
	@GetMapping
	public CartResponse getCart(@AuthenticationPrincipal String userId) {
		return cartService.getCart(userId);
	}

	/** Xoá toàn bộ giỏ sau khi tạo đơn. */
	@DeleteMapping
	public CartResponse clearCart(@AuthenticationPrincipal String userId) {
		cartService.clearCart(userId);
		return CartResponse.empty(userId);
	}

	/** Xoá một item đã được đặt khỏi giỏ. */
	@DeleteMapping("/items/{productId}")
	public CartResponse removeItem(@AuthenticationPrincipal String userId,
			@PathVariable String productId,
			@RequestParam(required = false) String variantId) {
		return cartService.removeItem(userId, productId, variantId);
	}
}
