package com.vmarket.product.exception;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.validation.ConstraintViolationException;
import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<Map<String, Object>> handleApi(ApiException ex, HttpServletRequest request) {
		return error(ex.getStatus(), ex.getCode(), ex.getMessage(), request.getRequestURI(), Map.of());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex,
			HttpServletRequest request) {
		Map<String, String> fields = new LinkedHashMap<>();
		ex.getBindingResult().getFieldErrors().forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
		return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Dữ liệu không hợp lệ", request.getRequestURI(), fields);
	}

	@ExceptionHandler(DuplicateKeyException.class)
	public ResponseEntity<Map<String, Object>> handleDuplicate(DuplicateKeyException ex, HttpServletRequest request) {
		return error(HttpStatus.CONFLICT, "DUPLICATE_VALUE", "Dữ liệu đã tồn tại", request.getRequestURI(), Map.of());
	}

	@ExceptionHandler({ ConstraintViolationException.class, MissingRequestHeaderException.class })
	public ResponseEntity<Map<String, Object>> handleRequestValidation(Exception ex, HttpServletRequest request) {
		return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Dữ liệu yêu cầu không hợp lệ",
				request.getRequestURI(), Map.of());
	}

	private ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message,
			String path, Map<String, String> fields) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("timestamp", Instant.now());
		body.put("status", status.value());
		body.put("error", code);
		body.put("message", message);
		body.put("path", path);
		if (!fields.isEmpty()) {
			body.put("fields", fields);
		}
		return ResponseEntity.status(status).body(body);
	}
}
