package com.vmarket.cart.exception;

/**
 * Redis không khả dụng (không kết nối được / lỗi ghi đọc). Ánh xạ thành 503 với
 * body lỗi chuẩn thay vì 500 mù mờ — client biết đây là lỗi hạ tầng có thể retry.
 */
public class CartStorageException extends RuntimeException {

	public CartStorageException(String message, Throwable cause) {
		super(message, cause);
	}
}
