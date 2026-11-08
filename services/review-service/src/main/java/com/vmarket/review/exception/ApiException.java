package com.vmarket.review.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;

/** Lỗi nghiệp vụ có mã ổn định; {@link GlobalExceptionHandler} chuyển thành body lỗi chuẩn. */
@Getter
public class ApiException extends RuntimeException {

	private final String code;
	private final HttpStatus status;

	public ApiException(String code, HttpStatus status, String message) {
		super(message);
		this.code = code;
		this.status = status;
	}

	public static ApiException badRequest(String code, String message) {
		return new ApiException(code, HttpStatus.BAD_REQUEST, message);
	}

	public static ApiException forbidden(String code, String message) {
		return new ApiException(code, HttpStatus.FORBIDDEN, message);
	}

	public static ApiException notFound(String code, String message) {
		return new ApiException(code, HttpStatus.NOT_FOUND, message);
	}

	public static ApiException conflict(String code, String message) {
		return new ApiException(code, HttpStatus.CONFLICT, message);
	}

	public static ApiException unprocessable(String code, String message) {
		return new ApiException(code, HttpStatus.UNPROCESSABLE_CONTENT, message);
	}

	/** Service phụ thuộc chưa sẵn sàng — client thử lại được. */
	public static ApiException unavailable(String code, String message) {
		return new ApiException(code, HttpStatus.SERVICE_UNAVAILABLE, message);
	}
}
