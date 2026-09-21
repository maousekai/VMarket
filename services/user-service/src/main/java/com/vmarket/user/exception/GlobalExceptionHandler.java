package com.vmarket.user.exception;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.vmarket.user.dto.ErrorResponse;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

/**
 * Chuyển mọi exception thành body chuẩn {@code { "error": { "code", "message" } }}.
 * Không bao giờ lộ stack trace ra client.
 *
 * <p>Vi phạm ràng buộc ở CSDL cũng được dịch ở đây thay vì bắt lại tại từng chỗ
 * ghi dữ liệu — xem {@link #handleDataIntegrity}.
 *
 * <p>401/403 <b>không</b> đi qua đây: Spring Security chặn từ tầng filter, trước
 * khi request tới controller. Hai trường hợp đó do {@code SecurityConfig} xử lý
 * bằng entry point / access denied handler riêng, nhưng trả ra đúng hình dạng body
 * này để client chỉ cần một hàm đọc lỗi.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	/**
	 * Partial unique index ở {@code V3__addresses_one_default_per_user.sql} — bất
	 * biến "tối đa một địa chỉ mặc định cho mỗi user".
	 */
	private static final String UQ_ONE_DEFAULT_ADDRESS = "uq_addresses_one_default_per_user";

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

	/**
	 * Cùng loại lỗi với {@link ConstraintViolationException} nhưng do cơ chế kiểm tra
	 * tham số có sẵn của Spring MVC ném ra (controller không có {@code @Validated}).
	 * Bắt để controller mới quên {@code @Validated} vẫn trả 400 chứ không phải 500.
	 */
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
	 * Ràng buộc CSDL bị vi phạm — gần như luôn là hai request ghi song song.
	 *
	 * <p>Đặt ở đây chứ không try/catch tại từng chỗ ghi: bất biến "một địa chỉ mặc
	 * định" bị chạm ở ba chỗ ({@code create}, {@code setDefault}, và bước chỉ định
	 * người kế nhiệm khi xoá), nên mỗi chỗ tự bắt lấy thì chỉ cần quên một chỗ là
	 * client nhận 500 cho một tình huống hoàn toàn bình thường.
	 *
	 * <p>Ba nhánh, cố ý không gộp:
	 * <ol>
	 *   <li>Đúng index một-mặc-định → {@code DEFAULT_ADDRESS_CONFLICT}, client thử lại
	 *       là xong.</li>
	 *   <li>Vi phạm UNIQUE khác → {@code DATA_CONFLICT}, vẫn là 409 vì thử lại có thể
	 *       thành công.</li>
	 *   <li>Vi phạm loại khác (NOT NULL, kiểu dữ liệu...) → 500. Đó là lỗi của chính
	 *       service, trả 409 sẽ xui client thử lại mãi cho một request không bao giờ
	 *       thành công.</li>
	 * </ol>
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
		String constraint = constraintNameOf(ex);

		if (constraint != null && constraint.toLowerCase(Locale.ROOT).contains(UQ_ONE_DEFAULT_ADDRESS)) {
			log.warn("Hai thao tác cùng đổi địa chỉ mặc định, CSDL từ chối request tới sau");
			return ResponseEntity.status(HttpStatus.CONFLICT)
					.body(ErrorResponse.of("DEFAULT_ADDRESS_CONFLICT",
							"Một thao tác khác đang đổi địa chỉ mặc định, vui lòng thử lại"));
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
	 * trong chuỗi nguyên nhân. Trả {@code null} nếu driver không nói tên.
	 *
	 * <p>So khớp bằng {@code contains} chứ không {@code equals}: PostgreSQL trả tên
	 * trần, còn H2 gắn thêm schema và hậu tố ({@code PUBLIC.UQ_..._INDEX_5}).
	 *
	 * <p>Viết tên đầy đủ thay vì import: trùng tên với
	 * {@code jakarta.validation.ConstraintViolationException} đã import ở trên, và
	 * hai lớp đó không liên quan gì tới nhau.
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
