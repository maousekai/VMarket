package com.vmarket.auth.exception;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.vmarket.auth.dto.ErrorResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * Chuyển mọi exception thành body chuẩn {@code { "error": { "code", "message" } }}.
 * Không bao giờ lộ stack trace ra client (root CLAUDE.md).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
		return ResponseEntity.status(ex.getStatus())
				.body(ErrorResponse.of(ex.getCode(), ex.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
		List<ErrorResponse.FieldError> details = ex.getBindingResult().getFieldErrors().stream()
				.map(this::toFieldError)
				.toList();
		return ResponseEntity.badRequest()
				.body(ErrorResponse.of("VALIDATION_ERROR", "Dữ liệu đầu vào không hợp lệ", details));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
		return ResponseEntity.badRequest()
				.body(ErrorResponse.of("MALFORMED_REQUEST", "Body không đọc được hoặc sai định dạng JSON"));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
		log.error("Unhandled exception", ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ErrorResponse.of("INTERNAL_ERROR", "Đã có lỗi xảy ra, vui lòng thử lại"));
	}

	private ErrorResponse.FieldError toFieldError(FieldError fe) {
		return new ErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage());
	}
}
