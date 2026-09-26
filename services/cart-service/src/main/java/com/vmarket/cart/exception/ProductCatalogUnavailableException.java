package com.vmarket.cart.exception;

/**
 * product-service không trả lời được (lỗi kết nối/timeout) hoặc trả lỗi ngoài 404
 * khi kiểm tra giá/tồn kho (FR-CART-03). Không phải lỗi của client —
 * {@code CheckoutService} chuyển thành issue {@code SERVICE_UNAVAILABLE} thay vì
 * chặn cả request bằng 500.
 */
public class ProductCatalogUnavailableException extends RuntimeException {

	public ProductCatalogUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
