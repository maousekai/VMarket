package com.vmarket.product.exception;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.validation.ConstraintViolationException;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Đưa MỌI lỗi HTTP về đúng envelope dùng chung (PBL6-15 Review 3, finding 4):
 * {timestamp, status, error: mã nghiệp vụ, message, path, [fields]}. Không còn
 * trường hợp trả envelope mặc định của Spring (vd lỗi JSON sai định dạng, 404,
 * 405, sai kiểu path variable) khiến client phải xử lý 2 cấu trúc khác nhau.
 */
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
		String firstMessage = ex.getBindingResult().getFieldErrors().isEmpty() ? null
				: ex.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
		return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
				firstMessage != null ? firstMessage : "Dữ liệu không hợp lệ", request.getRequestURI(), fields);
	}

	/** Body JSON sai định dạng / sai encoding — phải là 400 có envelope, không phải envelope mặc định. */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex,
			HttpServletRequest request) {
		return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Phần thân yêu cầu không phải JSON hợp lệ",
				request.getRequestURI(), Map.of());
	}

	/** Path variable / request param sai kiểu (vd id không phải ObjectId) — 400 có envelope. */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
			HttpServletRequest request) {
		return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Tham số đường dẫn không hợp lệ",
				request.getRequestURI(), Map.of());
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

	/** Không khớp route/controller nào — 404 có envelope thay vì trắng. */
	@ExceptionHandler({ NoHandlerFoundException.class, NoResourceFoundException.class })
	public ResponseEntity<Map<String, Object>> handleNotFound(Exception ex, HttpServletRequest request) {
		return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource không tồn tại", request.getRequestURI(), Map.of());
	}

	/** HTTP method không được controller hỗ trợ — 405 có envelope. */
	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
			HttpServletRequest request) {
		return error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Phương thức không được hỗ trợ",
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
