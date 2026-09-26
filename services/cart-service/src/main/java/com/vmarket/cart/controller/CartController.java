package com.vmarket.cart.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.cart.dto.AddCartItemRequest;
import com.vmarket.cart.dto.CartResponse;
import com.vmarket.cart.dto.CheckoutCheckResponse;
import com.vmarket.cart.dto.UpdateCartItemRequest;
import com.vmarket.cart.service.CartService;
import com.vmarket.cart.service.CheckoutService;

import jakarta.validation.Valid;

/**
 * API giỏ hàng (FR-CART-01/02/03).
 *
 * <p><b>Danh tính người dùng:</b> lấy từ {@code SecurityContext} qua
 * {@link AuthenticationPrincipal} — do {@code CartAuthenticationFilter} xác lập,
 * hoặc từ access token đã verify (request từ browser qua gateway), hoặc từ khoá
 * nội bộ + {@code X-User-Id} (order-service gọi lúc đặt hàng). Controller KHÔNG
 * đọc header danh tính trực tiếp: tin thẳng {@code X-User-Id} là cho phép bất kỳ
 * ai gọi được cổng 8085 mạo danh người khác (P1 của review PR #23).
 *
 * <p>Endpoint công khai duy nhất là {@code GET /api/cart/health}.
 */
@RestController
@RequestMapping("/api/cart")
public class CartController {

	private final CartService cartService;
	private final CheckoutService checkoutService;

	public CartController(CartService cartService, CheckoutService checkoutService) {
		this.cartService = cartService;
		this.checkoutService = checkoutService;
	}

	/**
	 * Xem giỏ hàng, nhóm theo gian hàng với tạm tính từng nhóm (FR-CART-02).
	 */
	@GetMapping
	public CartResponse getCart(@AuthenticationPrincipal String userId) {
		return cartService.getCart(userId);
	}

	/**
	 * Thêm sản phẩm vào giỏ (FR-CART-01). Cùng sản phẩm + biến thể thì cộng dồn.
	 */
	@PostMapping("/items")
	public ResponseEntity<CartResponse> addItem(@AuthenticationPrincipal String userId,
			@Valid @RequestBody AddCartItemRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(cartService.addItem(userId, request));
	}

	/**
	 * Cập nhật số lượng một item (FR-CART-01).
	 *
	 * @param productId item cần cập nhật
	 * @param variantId biến thể (query, tuỳ chọn)
	 */
	@PatchMapping("/items/{productId}")
	public CartResponse updateQuantity(@AuthenticationPrincipal String userId,
			@PathVariable String productId,
			@RequestParam(required = false) String variantId,
			@Valid @RequestBody UpdateCartItemRequest request) {
		return cartService.updateQuantity(userId, productId, variantId, request.quantity());
	}

	/**
	 * Xoá một item khỏi giỏ (FR-CART-01).
	 */
	@DeleteMapping("/items/{productId}")
	public CartResponse removeItem(@AuthenticationPrincipal String userId,
			@PathVariable String productId,
			@RequestParam(required = false) String variantId) {
		return cartService.removeItem(userId, productId, variantId);
	}

	/**
	 * Xoá toàn bộ giỏ (FR-CART-01).
	 */
	@DeleteMapping
	public CartResponse clearCart(@AuthenticationPrincipal String userId) {
		cartService.clearCart(userId);
		return CartResponse.empty(userId);
	}

	/**
	 * Kiểm tra giá/tồn kho mới nhất trước khi vào thanh toán (FR-CART-03).
	 * Trả 200 kèm {@code ok} và danh sách cảnh báo (nếu có).
	 */
	@PostMapping("/validate")
	public CheckoutCheckResponse validate(@AuthenticationPrincipal String userId) {
		return checkoutService.validate(userId);
	}
}
