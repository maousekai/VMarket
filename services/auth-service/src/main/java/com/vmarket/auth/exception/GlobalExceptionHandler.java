package com.vmarket.auth.exception;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.vmarket.auth.dto.ErrorResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * Chuyển mọi exception thành body chuẩn {@code { "error": { "code", "message" } }}.
 * Không bao giờ lộ stack trace ra client.
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

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
		return ResponseEntity.badRequest()
				.body(ErrorResponse.of("VALIDATION_ERROR", "Tham số '" + ex.getName() + "' sai kiểu dữ liệu"));
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(ErrorResponse.of("NOT_FOUND", "Không tìm thấy tài nguyên"));
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
		return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
				.body(ErrorResponse.of("METHOD_NOT_ALLOWED", "Phương thức " + ex.getMethod() + " không được hỗ trợ"));
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
		return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
				.body(ErrorResponse.of("UNSUPPORTED_MEDIA_TYPE", "Content-Type không được hỗ trợ"));
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
