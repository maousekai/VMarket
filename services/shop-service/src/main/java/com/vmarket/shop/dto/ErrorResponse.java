package com.vmarket.shop.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body lỗi chuẩn dùng chung cho mọi endpoint:
 * {@code { "error": { "code": "...", "message": "..." } }}.
 *
 * <p>Giữ nguyên hình dạng với auth-service / user-service để frontend chỉ cần một hàm
 * xử lý lỗi cho toàn hệ thống.
 */
@Schema(name = "ErrorResponse", description = "Body lỗi chuẩn")
public record ErrorResponse(Error error) {

	public record Error(
			@Schema(example = "VALIDATION_ERROR") String code,
			@Schema(example = "Dữ liệu đầu vào không hợp lệ") String message,
			@JsonInclude(JsonInclude.Include.NON_EMPTY) List<FieldError> details) {
	}

	public record FieldError(
			@Schema(example = "contactPhone") String field,
			@Schema(example = "Số điện thoại không đúng định dạng") String message) {
	}

	public static ErrorResponse of(String code, String message) {
		return new ErrorResponse(new Error(code, message, List.of()));
	}

	public static ErrorResponse of(String code, String message, List<FieldError> details) {
		return new ErrorResponse(new Error(code, message, details));
	}
}
