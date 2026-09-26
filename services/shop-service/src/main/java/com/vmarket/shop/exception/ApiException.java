package com.vmarket.shop.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;

/**
 * Lỗi nghiệp vụ có mã máy đọc được ổn định. {@link GlobalExceptionHandler} chuyển
 * thành body chuẩn {@code { "error": { "code", "message" } }} của dự án — cùng hình
 * dạng với {@code ApiException} của auth-service / user-service.
 */
@Getter
public class ApiException extends RuntimeException {

	private final String code;
	private final HttpStatus status;

	public ApiException(String code, HttpStatus status, String message) {
		super(message);
		this.code = code;
		this.status = status;
	}

	public static ApiException notFound(String code, String message) {
		return new ApiException(code, HttpStatus.NOT_FOUND, message);
	}

	public static ApiException conflict(String code, String message) {
		return new ApiException(code, HttpStatus.CONFLICT, message);
	}
}
