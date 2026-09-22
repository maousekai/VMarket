package com.vmarket.cart.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.cart.dto.AddCartItemRequest;
import com.vmarket.cart.dto.CartResponse;
import com.vmarket.cart.dto.CheckoutCheckResponse;
import com.vmarket.cart.dto.UpdateCartItemRequest;
import com.vmarket.cart.exception.ApiException;
import com.vmarket.cart.service.CartService;
import com.vmarket.cart.service.CheckoutService;

import jakarta.validation.Valid;

/**
 * API giỏ hàng (FR-CART-01/02/03).
 *
 * <p><b>Danh tính người dùng:</b> gateway (PBL6-38) đã verify JWT rồi gắn
 * {@code X-User-Id} vào header, đồng thời strip mọi giá trị {@code X-User-*}
 * client tự gửi lên (chống giả mạo). Service này chỉ đọc header — nếu chạy
 * controller trực tiếp mà không qua gateway thì thiếu header → 401.
 */
@RestController
@RequestMapping("/api/cart")
public class CartController {

	public static final String HEADER_USER_ID = "X-User-Id";

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
	public CartResponse getCart(@RequestHeader(name = HEADER_USER_ID, required = false) String userId) {
		return cartService.getCart(requireUserId(userId));
	}

	/**
	 * Thêm sản phẩm vào giỏ (FR-CART-01). Cùng sản phẩm + biến thể thì cộng dồn.
	 */
	@PostMapping("/items")
	public ResponseEntity<CartResponse> addItem(
			@RequestHeader(name = HEADER_USER_ID, required = false) String userId,
			@Valid @RequestBody AddCartItemRequest request) {
		CartResponse response = cartService.addItem(requireUserId(userId), request);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	/**
	 * Cập nhật số lượng một item (FR-CART-01).
	 *
	 * @param productId item cần cập nhật
	 * @param variantId biến thể (query, tuỳ chọn)
	 */
	@PatchMapping("/items/{productId}")
	public CartResponse updateQuantity(
			@RequestHeader(name = HEADER_USER_ID, required = false) String userId,
			@PathVariable String productId,
			@RequestParam(required = false) String variantId,
			@Valid @RequestBody UpdateCartItemRequest request) {
		return cartService.updateQuantity(requireUserId(userId), productId, variantId, request.quantity());
	}

	/**
	 * Xoá một item khỏi giỏ (FR-CART-01).
	 */
	@DeleteMapping("/items/{productId}")
	public CartResponse removeItem(
			@RequestHeader(name = HEADER_USER_ID, required = false) String userId,
			@PathVariable String productId,
			@RequestParam(required = false) String variantId) {
		return cartService.removeItem(requireUserId(userId), productId, variantId);
	}

	/**
	 * Xoá toàn bộ giỏ (FR-CART-01).
	 */
	@DeleteMapping
	public CartResponse clearCart(@RequestHeader(name = HEADER_USER_ID, required = false) String userId) {
		cartService.clearCart(requireUserId(userId));
		return CartResponse.empty(requireUserId(userId));
	}

	/**
	 * Kiểm tra giá/tồn kho mới nhất trước khi vào thanh toán (FR-CART-03).
	 * Trả 200 kèm {@code ok} và danh sách cảnh báo (nếu có).
	 */
	@PostMapping("/validate")
	public CheckoutCheckResponse validate(
			@RequestHeader(name = HEADER_USER_ID, required = false) String userId) {
		return checkoutService.validate(requireUserId(userId));
	}

	private static String requireUserId(String userId) {
		if (userId == null || userId.isBlank()) {
			throw ApiException.unauthorized("UNAUTHORIZED",
					"Cần đăng nhập để thao tác với giỏ hàng");
		}
		return userId;
	}
}
