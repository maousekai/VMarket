package com.vmarket.user.exception;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.vmarket.user.dto.ErrorResponse;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

/**
 * Chuyển mọi exception thành body chuẩn {@code { "error": { "code", "message" } }}.
 * Không bao giờ lộ stack trace ra client.
 *
 * <p>401/403 <b>không</b> đi qua đây: Spring Security chặn từ tầng filter, trước
 * khi request tới controller. Hai trường hợp đó do {@code SecurityConfig} xử lý
 * bằng entry point / access denied handler riêng, nhưng trả ra đúng hình dạng body
 * này để client chỉ cần một hàm đọc lỗi.
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

	/**
	 * Vi phạm ràng buộc trên tham số query/path ({@code @Min}, {@code @Max}... của
	 * lớp có {@code @Validated}). Khác {@link MethodArgumentNotValidException} vốn
	 * chỉ dành cho body — hai loại này đi hai đường riêng nên phải bắt cả hai, nếu
	 * không {@code ?page=-1} sẽ trả 500 thay vì 400.
	 */
	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
		List<ErrorResponse.FieldError> details = ex.getConstraintViolations().stream()
				.map(v -> new ErrorResponse.FieldError(lastNode(v.getPropertyPath().toString()), v.getMessage()))
				.toList();
		return ResponseEntity.badRequest()
				.body(ErrorResponse.of("VALIDATION_ERROR", "Tham số không hợp lệ", details));
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

	/**
	 * Từ chối quyền do {@code @PreAuthorize} trên method.
	 *
	 * <p>Phải bắt tường minh ở đây. {@code @PreAuthorize} chạy <b>bên trong</b> lời
	 * gọi controller, nên exception của nó rơi vào {@code @RestControllerAdvice}
	 * TRƯỚC khi tới được {@code ExceptionTranslationFilter} của Spring Security —
	 * handler {@code Exception} bên dưới sẽ nuốt mất và trả 500 thay vì 403.
	 * (Cơ chế {@code accessDeniedHandler} trong {@code SecurityConfig} chỉ áp dụng
	 * cho phần bị chặn ở tầng filter, không áp dụng cho method security.)
	 *
	 * <p>{@code AuthorizationDeniedException} kế thừa {@code AccessDeniedException}
	 * nên khai một cái là phủ cả hai.
	 */
	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(ErrorResponse.of("FORBIDDEN", "Bạn không có quyền thực hiện thao tác này"));
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

	/** "search.page" -> "page": client chỉ quan tâm tên tham số, không phải tên method. */
	private static String lastNode(String propertyPath) {
		int dot = propertyPath.lastIndexOf('.');
		return dot < 0 ? propertyPath : propertyPath.substring(dot + 1);
	}
}
