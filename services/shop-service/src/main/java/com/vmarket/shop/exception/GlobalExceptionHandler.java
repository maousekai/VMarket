package com.vmarket.shop.exception;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.vmarket.shop.dto.ErrorResponse;
import com.vmarket.shop.entity.Shop;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

/**
 * Chuyển mọi exception thành body chuẩn {@code { "error": { "code", "message" } }}.
 * Không bao giờ lộ stack trace ra client. Cùng khuôn với user-service.
 *
 * <p>401/403 ở tầng filter <b>không</b> đi qua đây — {@code SecurityConfig} tự ghi body
 * cùng hình dạng.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	/** SQLSTATE 23505 = {@code unique_violation}; PostgreSQL và H2 dùng chung mã này. */
	private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

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
	 * Vi phạm ràng buộc trên tham số query/path ({@code @Min}, {@code @Max}... của lớp
	 * có {@code @Validated}). Khác {@link MethodArgumentNotValidException} vốn chỉ dành
	 * cho body — không bắt thì {@code ?page=-1} trả 500 thay vì 400.
	 */
	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
		List<ErrorResponse.FieldError> details = ex.getConstraintViolations().stream()
				.map(v -> new ErrorResponse.FieldError(lastNode(v.getPropertyPath().toString()), v.getMessage()))
				.toList();
		return ResponseEntity.badRequest()
				.body(ErrorResponse.of("VALIDATION_ERROR", "Tham số không hợp lệ", details));
	}

	/** Cùng loại với trên nhưng do cơ chế kiểm tra tham số có sẵn của Spring MVC ném ra. */
	@ExceptionHandler(HandlerMethodValidationException.class)
	public ResponseEntity<ErrorResponse> handleMethodValidation(HandlerMethodValidationException ex) {
		List<ErrorResponse.FieldError> details = ex.getParameterValidationResults().stream()
				.flatMap(result -> result.getResolvableErrors().stream()
						.map(error -> new ErrorResponse.FieldError(
								result.getMethodParameter().getParameterName(), error.getDefaultMessage())))
				.toList();
		return ResponseEntity.badRequest()
				.body(ErrorResponse.of("VALIDATION_ERROR", "Tham số không hợp lệ", details));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
		return ResponseEntity.badRequest()
				.body(ErrorResponse.of("MALFORMED_REQUEST", "Body không đọc được hoặc sai định dạng JSON"));
	}

	/** Vd {@code ?status=ABC} không phải một {@code ShopStatus} hợp lệ. */
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
	 * {@code @Version} của {@link Shop} phát hiện hai thao tác ghi đè nhau — vd Admin bấm
	 * duyệt đúng lúc người bán lưu hồ sơ. Trả 409 để bên tới sau tải lại rồi thao tác
	 * trên dữ liệu mới nhất, thay vì âm thầm ghi đè.
	 */
	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	public ResponseEntity<ErrorResponse> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
		log.warn("Hai thao tác cùng sửa một gian hàng, request tới sau bị từ chối");
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(ErrorResponse.of("CONCURRENT_MODIFICATION",
						"Gian hàng vừa được cập nhật bởi một thao tác khác, vui lòng tải lại rồi thử lại"));
	}

	/**
	 * Ràng buộc CSDL bị vi phạm — gần như luôn là hai request ghi song song cùng lọt qua
	 * bước kiểm tra trước ở tầng service (vd bấm "Đăng ký" hai lần liên tiếp).
	 *
	 * <p>Ba nhánh, cố ý không gộp:
	 * <ol>
	 *   <li>Đúng ràng buộc đã biết → mã lỗi nghiệp vụ tương ứng (như khi service tự
	 *       phát hiện), để client không phải xử lý hai mã cho cùng một tình huống.</li>
	 *   <li>Vi phạm UNIQUE khác → {@code DATA_CONFLICT}, vẫn là 409.</li>
	 *   <li>Vi phạm loại khác (NOT NULL, kiểu dữ liệu...) → 500: lỗi của chính service,
	 *       trả 409 sẽ xui client thử lại mãi một request không bao giờ thành công.</li>
	 * </ol>
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
		String constraint = constraintNameOf(ex);
		String normalized = constraint == null ? "" : constraint.toLowerCase(Locale.ROOT);

		if (normalized.contains(Shop.UQ_OWNER_ID)) {
			return ResponseEntity.status(HttpStatus.CONFLICT)
					.body(ErrorResponse.of("SHOP_ALREADY_EXISTS", "Bạn đã đăng ký một gian hàng"));
		}
		if (normalized.contains(Shop.UQ_NAME_KEY)) {
			return ResponseEntity.status(HttpStatus.CONFLICT)
					.body(ErrorResponse.of("SHOP_NAME_TAKEN", "Tên gian hàng đã được sử dụng"));
		}
		if (isUniqueViolation(ex)) {
			log.warn("Vi phạm ràng buộc duy nhất (constraint={})", constraint);
			return ResponseEntity.status(HttpStatus.CONFLICT)
					.body(ErrorResponse.of("DATA_CONFLICT",
							"Dữ liệu vừa bị thay đổi bởi một thao tác khác, vui lòng thử lại"));
		}

		log.error("Vi phạm toàn vẹn dữ liệu ngoài dự kiến (constraint={})", constraint, ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ErrorResponse.of("INTERNAL_ERROR", "Đã có lỗi xảy ra, vui lòng thử lại"));
	}

	/**
	 * Phòng hờ: hiện phân quyền nằm hết ở tầng filter ({@code SecurityConfig}), nhưng nếu
	 * sau này ai thêm {@code @PreAuthorize}, exception của nó rơi vào đây TRƯỚC khi tới
	 * được Spring Security — không bắt thì handler {@code Exception} bên dưới trả 500.
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

	/**
	 * Tên ràng buộc bị vi phạm, lấy từ {@code org.hibernate.exception.ConstraintViolationException}
	 * trong chuỗi nguyên nhân; {@code null} nếu driver không nói tên. So khớp bằng
	 * {@code contains}: PostgreSQL trả tên trần, H2 gắn thêm schema và hậu tố.
	 */
	private static String constraintNameOf(Throwable ex) {
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (t instanceof org.hibernate.exception.ConstraintViolationException hibernateEx) {
				return hibernateEx.getConstraintName();
			}
			if (t == t.getCause()) {
				break;
			}
		}
		return null;
	}

	/** Có phải vi phạm UNIQUE không — đọc SQLSTATE thay vì dò chuỗi thông báo lỗi. */
	private static boolean isUniqueViolation(Throwable ex) {
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (t instanceof SQLException sqlEx && SQLSTATE_UNIQUE_VIOLATION.equals(sqlEx.getSQLState())) {
				return true;
			}
			if (t == t.getCause()) {
				break;
			}
		}
		return false;
	}

	/** "search.page" -> "page": client chỉ quan tâm tên tham số, không phải tên method. */
	private static String lastNode(String propertyPath) {
		int dot = propertyPath.lastIndexOf('.');
		return dot < 0 ? propertyPath : propertyPath.substring(dot + 1);
	}
}
