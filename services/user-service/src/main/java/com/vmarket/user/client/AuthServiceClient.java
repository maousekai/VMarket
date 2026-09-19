package com.vmarket.user.client;

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
 *   <li>Không kết nối được / quá thời gian → 503 {@code AUTH_SERVICE_UNAVAILABLE}.</li>
 * </ul>
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
				.toBodilessEntity());
	}

	/** FR-USER-04. */
	public AuthAccount getAccount(String userId) {
		return call(() -> restClient.get()
				.uri("/internal/users/{userId}", userId)
				.retrieve()
				.body(AuthAccount.class));
	}

	/** FR-USER-04. */
	public AuthAccountPage searchAccounts(AuthAccountSearch search) {
		return call(() -> restClient.post()
				.uri("/internal/users/search")
				.contentType(MediaType.APPLICATION_JSON)
				.body(search)
				.retrieve()
				.body(AuthAccountPage.class));
	}

	/** FR-USER-04 — khoá. */
	public AuthAccount suspend(String userId, String reason, String actorId) {
		return call(() -> restClient.put()
				.uri("/internal/users/{userId}/suspension", userId)
				.contentType(MediaType.APPLICATION_JSON)
				.body(new SuspendBody(reason, actorId))
				.retrieve()
				.body(AuthAccount.class));
	}

	/** FR-USER-04 — mở khoá. */
	public AuthAccount unsuspend(String userId) {
		return call(() -> restClient.delete()
				.uri("/internal/users/{userId}/suspension", userId)
				.retrieve()
				.body(AuthAccount.class));
	}

	// --- helpers -------------------------------------------------------------

	private <T> T call(Supplier<T> request) {
		T result;
		try {
			result = request.get();
		} catch (RestClientResponseException ex) {
			throw translate(ex);
		} catch (ResourceAccessException ex) {
			log.error("Không kết nối được auth-service: {}", ex.getMessage());
			throw unavailable();
		} catch (RestClientException ex) {
			log.error("Response không hợp lệ từ auth-service: {}", ex.getMessage());
			throw upstreamError();
		}
		return result;
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

	record ChangePasswordBody(String currentPassword, String newPassword) {
	}

	record SuspendBody(String reason, String actorId) {
	}

	/** Body lỗi chuẩn của dự án {@code { "error": { "code", "message" } }}. */
	record ErrorBody(ErrorDetail error) {
	}

	record ErrorDetail(String code, String message) {
	}
}
