package com.vmarket.auth.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body lỗi chuẩn dùng chung cho mọi endpoint:
 * {@code { "error": { "code": "...", "message": "..." } }}.
 * {@code details} là phần mở rộng tuỳ chọn cho lỗi validate từng trường.
 */
@Schema(name = "ErrorResponse", description = "Body lỗi chuẩn")
public record ErrorResponse(Error error) {

	public record Error(
			@Schema(example = "EMAIL_ALREADY_EXISTS") String code,
			@Schema(example = "Email đã được sử dụng") String message,
			@JsonInclude(JsonInclude.Include.NON_EMPTY) List<FieldError> details) {
	}

	public record FieldError(
			@Schema(example = "password") String field,
			@Schema(example = "Mật khẩu phải có ít nhất 1 ký tự đặc biệt") String message) {
	}

	public static ErrorResponse of(String code, String message) {
		return new ErrorResponse(new Error(code, message, List.of()));
	}

	public static ErrorResponse of(String code, String message, List<FieldError> details) {
		return new ErrorResponse(new Error(code, message, details));
	}
}
