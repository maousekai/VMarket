package com.vmarket.user.client;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.vmarket.user.config.AuthServiceProperties;
import com.vmarket.user.exception.ApiException;

import lombok.extern.slf4j.Slf4j;

/**
 * Gọi API nội bộ {@code /internal/users/**} của auth-service.
 *
 * <p><b>Ánh xạ lỗi</b> — người dùng cuối không bao giờ thấy chi tiết hạ tầng:
 * <ul>
 *   <li>auth-service trả lỗi nghiệp vụ 4xx ({@code INVALID_CURRENT_PASSWORD},
 *       {@code USER_NOT_FOUND}, {@code ACCOUNT_LOCKED}...) → chuyển tiếp nguyên mã,
 *       HTTP status và thông báo: đó là câu trả lời đúng cho người dùng.</li>
 *   <li>auth-service trả 401/403 → <b>không</b> chuyển tiếp. Đó là lỗi cấu hình
 *       {@code INTERNAL_API_KEY} giữa hai service; trả 401 cho client sẽ khiến frontend
 *       tưởng access token của người dùng hết hạn và đăng xuất họ. → 502.</li>
 *   <li>auth-service 5xx / body không đọc được → 502 {@code AUTH_SERVICE_ERROR}.</li>
 *   <li><b>Chưa tới được auth-service</b> (connection refused, sai host, hết thời gian
 *       <i>kết nối</i>) → 503 {@code AUTH_SERVICE_UNAVAILABLE}. Request chắc chắn chưa
 *       chạy nên bảo người dùng thử lại là đúng.</li>
 *   <li><b>Đã tới nơi nhưng không biết kết quả</b> (hết thời gian <i>đọc</i>, đứt kết nối
 *       giữa chừng) → mã riêng do từng lời gọi quyết định, xem dưới.</li>
 * </ul>
 *
 * <p><b>Vì sao phải tách hai nhóm cuối.</b> Read timeout xảy ra <i>sau</i> khi
 * auth-service đã nhận request. Nếu bên đó vẫn chạy xong thì mật khẩu đã đổi và mọi
 * refresh token đã bị thu hồi. Gộp chung vào 503 "vui lòng thử lại sau" sẽ đẩy người
 * dùng vào đúng cái bẫy: họ bấm thử lại với mật khẩu cũ → {@code INVALID_CURRENT_PASSWORD}
 * → bộ đếm khoá tăng; vài lần là khoá tài khoản 15 phút dù họ không làm gì sai.
 *
 * <p><b>Phân loại theo kiểu exception, không dò chuỗi thông báo.</b> Chỉ những lỗi
 * <i>chắc chắn</i> chưa gửi được byte nào mới vào nhóm 503: {@link ConnectException},
 * {@link UnknownHostException}, {@link NoRouteToHostException},
 * {@link HttpConnectTimeoutException}. Mọi thứ còn lại mặc định là "không rõ kết quả" —
 * nghiêng về phía an toàn, vì đoán nhầm theo hướng "chắc chắn hỏng" mới là cái gây hại.
 *
 * <p>Không log body request/response: có mật khẩu (NFR-SEC-06).
 */
@Slf4j
public class AuthServiceClient {

	private final RestClient restClient;

	public AuthServiceClient(RestClient restClient) {
		this.restClient = restClient;
	}

	/**
	 * Gắn base URL + header khoá nội bộ vào builder. Tách riêng để test dùng lại đúng
	 * cấu hình này với {@code MockRestServiceServer} (request factory do caller đặt).
	 */
	public static RestClient buildRestClient(RestClient.Builder builder, AuthServiceProperties properties) {
		return builder
				.baseUrl(properties.getBaseUrl())
				.defaultHeader(AuthServiceProperties.INTERNAL_API_KEY_HEADER, properties.getInternalApiKey())
				.build();
	}

	/** FR-USER-03. */
	public void changePassword(String userId, String currentPassword, String newPassword) {
		call(() -> restClient.put()
				.uri("/internal/users/{userId}/password", userId)
				.contentType(MediaType.APPLICATION_JSON)
				.body(new ChangePasswordBody(currentPassword, newPassword))
				.retrieve()
				.toBodilessEntity(),
				AuthServiceClient::passwordChangeUnknown);
	}

	// --- helpers -------------------------------------------------------------

	/**
	 * @param onUnknownOutcome lỗi trả về khi request đã tới auth-service nhưng không
	 *                         biết nó có chạy xong hay không. Mỗi lời gọi tự quyết định
	 *                         vì lời khuyên cho người dùng phụ thuộc vào thao tác: đọc
	 *                         dữ liệu thì cứ tải lại, còn ghi dữ liệu thì tuyệt đối
	 *                         không được xui họ "thử lại ngay".
	 */
	private <T> T call(Supplier<T> request, Supplier<ApiException> onUnknownOutcome) {
		T result;
		try {
			result = request.get();
		} catch (RestClientResponseException ex) {
			throw translate(ex);
		} catch (ResourceAccessException ex) {
			if (neverReachedAuthService(ex)) {
				log.error("Không kết nối được auth-service: {}", ex.getMessage());
				throw unavailable();
			}
			log.error("Mất kết nối với auth-service SAU khi đã gửi request — không rõ kết quả: {}",
					ex.getMessage());
			throw onUnknownOutcome.get();
		} catch (RestClientException ex) {
			log.error("Response không hợp lệ từ auth-service: {}", ex.getMessage());
			throw upstreamError();
		}
		return result;
	}

	/**
	 * Chỉ {@code true} khi chắc chắn chưa byte nào tới auth-service.
	 *
	 * <p>Mặc định trả {@code false} (coi như không rõ) cho mọi lỗi lạ: nói "chắc chắn
	 * hỏng" trong khi thao tác đã chạy xong là kiểu đoán nhầm gây hại, còn nói "không
	 * rõ" trong khi nó thật sự hỏng thì người dùng chỉ mất công thử mật khẩu mới một lần.
	 */
	private static boolean neverReachedAuthService(Throwable ex) {
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (t instanceof HttpConnectTimeoutException
					|| t instanceof ConnectException
					|| t instanceof UnknownHostException
					|| t instanceof NoRouteToHostException) {
				return true;
			}
			if (t == t.getCause()) {
				break;
			}
		}
		return false;
	}

	private ApiException translate(RestClientResponseException ex) {
		HttpStatusCode status = ex.getStatusCode();
		if (status.value() == 401 || status.value() == 403) {
			log.error("auth-service từ chối khoá nội bộ (HTTP {}) — kiểm tra INTERNAL_API_KEY hai service có trùng không",
					status.value());
			return upstreamError();
		}
		HttpStatus known = HttpStatus.resolve(status.value());
		if (known != null && known.is4xxClientError()) {
			ErrorBody body = readErrorBody(ex);
			if (body != null && body.error() != null && body.error().code() != null) {
				return new ApiException(body.error().code(), known, body.error().message());
			}
		}
		log.error("auth-service trả lỗi không mong đợi HTTP {}", status.value());
		return upstreamError();
	}

	private static ErrorBody readErrorBody(RestClientResponseException ex) {
		try {
			return ex.getResponseBodyAs(ErrorBody.class);
		} catch (RuntimeException parseFailure) {
			return null;
		}
	}

	private static ApiException unavailable() {
		return new ApiException("AUTH_SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
				"Dịch vụ tài khoản tạm thời không khả dụng, vui lòng thử lại sau");
	}

	private static ApiException upstreamError() {
		return new ApiException("AUTH_SERVICE_ERROR", HttpStatus.BAD_GATEWAY,
				"Dịch vụ tài khoản gặp lỗi, vui lòng thử lại sau");
	}

	/**
	 * Đổi mật khẩu mà không biết kết quả.
	 *
	 * <p>Thông báo cố ý KHÔNG có chữ "thử lại": nếu auth-service đã chạy xong thì mật
	 * khẩu mới đang có hiệu lực, thử lại bằng mật khẩu cũ chỉ làm tăng bộ đếm khoá.
	 * Cách kiểm tra rẻ nhất và không có tác dụng phụ là đăng nhập bằng mật khẩu mới.
	 */
	private static ApiException passwordChangeUnknown() {
		return new ApiException("PASSWORD_CHANGE_UNKNOWN", HttpStatus.GATEWAY_TIMEOUT,
				"Chưa rõ mật khẩu đã đổi hay chưa. Hãy đăng nhập lại bằng mật khẩu MỚI; "
						+ "nếu không vào được thì mật khẩu cũ vẫn còn hiệu lực và bạn có thể đổi lại.");
	}

	record ChangePasswordBody(String currentPassword, String newPassword) {
	}

	/** Body lỗi chuẩn của dự án {@code { "error": { "code", "message" } }}. */
	record ErrorBody(ErrorDetail error) {
	}

	record ErrorDetail(String code, String message) {
	}
}
