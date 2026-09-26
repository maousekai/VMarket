package com.vmarket.cart.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body lỗi chuẩn dùng chung cho mọi endpoint:
 * {@code { "error": { "code": "...", "message": "..." } }}.
 *
 * <p>Cùng hình dạng với auth-service / user-service để frontend chỉ cần một hàm
 * xử lý lỗi cho toàn hệ thống.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorResponse(Error error) {

	public record Error(String code, String message, List<FieldError> details) {
	}

	public record FieldError(String field, String message) {
	}

	public static ErrorResponse of(String code, String message) {
		return new ErrorResponse(new Error(code, message, List.of()));
	}

	public static ErrorResponse of(String code, String message, List<FieldError> details) {
		return new ErrorResponse(new Error(code, message, details));
	}
}
