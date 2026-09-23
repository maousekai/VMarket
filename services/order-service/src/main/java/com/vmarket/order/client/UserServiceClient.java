package com.vmarket.order.client;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.vmarket.order.exception.ApiException;

import lombok.extern.slf4j.Slf4j;

/**
 * Gọi API địa chỉ của user-service khi đặt hàng (FR-ORDER-01).
 *
 * <p><b>Xác thực đi bằng token của người dùng, không phải khoá nội bộ.</b>
 * user-service tự verify JWT trên mọi endpoint ({@code /api/users/**} đều bắt buộc
 * đăng nhập, không có {@code /internal} nào cho service khác), nên cách duy nhất
 * để đọc địa chỉ là forward nguyên header {@code Authorization} của request gốc —
 * token của chính người đang đặt hàng.
 *
 * <p>Hệ quả quan trọng: <b>không thể đọc địa chỉ của người khác</b>. Địa chỉ nạp
 * được luôn thuộc về người sở hữu token, nên {@code addressId} nhận từ client chỉ
 * là con trỏ vào sổ địa chỉ của chính họ (IDOR bị chặn từ phía user-service);
 * order-service vẫn so khớp {@code userId} một lần nữa ở {@code OrderService} để
 * lỗi rõ ràng hơn nếu hai service lệch nhau.
 *
 * <p><b>Ánh xạ lỗi</b> — như {@code CartServiceClient}: 4xx chuyển tiếp (404 của
 * user-service → {@code ADDRESS_NOT_FOUND}), chưa tới nơi → 503, lỗi hạ tầng →
 * 502. Đọc địa chỉ là thao tác vô hại nên "không rõ kết quả" cũng gộp vào 503.
 */
@Slf4j
public class UserServiceClient {

	private final RestClient restClient;

	public UserServiceClient(RestClient restClient) {
		this.restClient = restClient;
	}

	/**
	 * Đọc một địa chỉ trong sổ của người đang đăng nhập.
	 *
	 * @param bearerToken giá trị header {@code Authorization} gốc, nguyên vẹn
	 *                    ({@code "Bearer eyJ..."})
	 * @throws ApiException 404 {@code ADDRESS_NOT_FOUND} khi id không thuộc sổ của
	 *                      người này
	 * @throws ApiException 503 {@code USER_SERVICE_UNAVAILABLE} khi chưa tới được
	 * @throws ApiException 502 {@code USER_SERVICE_ERROR} khi user-service hỏng
	 */
	public AddressView getAddress(String bearerToken, String addressId) {
		try {
			AddressView address = restClient.get()
					.uri("/api/users/me/addresses/{id}", addressId)
					.header("Authorization", bearerToken)
					.retrieve()
					.body(AddressView.class);
			if (address == null) {
				log.error("user-service trả body rỗng cho GET /api/users/me/addresses/{}", addressId);
				throw upstreamError();
			}
			return address;
		} catch (RestClientResponseException ex) {
			throw translate(ex);
		} catch (ResourceAccessException ex) {
			if (neverReachedUserService(ex)) {
				log.error("Không kết nối được user-service: {}", ex.getMessage());
				throw unavailable();
			}
			log.error("Mất kết nối với user-service khi đọc địa chỉ: {}", ex.getMessage());
			throw unavailable();
		} catch (RestClientException ex) {
			log.error("Response không hợp lệ từ user-service: {}", ex.getMessage());
			throw upstreamError();
		}
	}

	private static boolean neverReachedUserService(Throwable ex) {
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

	/** Chuyển tiếp lỗi nghiệp vụ 4xx của user-service; còn lại gộp thành lỗi hạ tầng. */
	private ApiException translate(RestClientResponseException ex) {
		HttpStatusCode status = ex.getStatusCode();
		if (status.is5xxServerError()) {
			log.error("user-service trả lỗi HTTP {}", status.value());
			return unavailable();
		}
		var known = org.springframework.http.HttpStatus.resolve(status.value());
		if (known != null && known.is4xxClientError()) {
			ErrorBody body = readErrorBody(ex);
			if (body != null && body.error() != null && body.error().code() != null) {
				return new ApiException(body.error().code(), known, body.error().message());
			}
		}
		log.error("user-service trả lỗi không mong đợi HTTP {}", status.value());
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
		return new ApiException("USER_SERVICE_UNAVAILABLE", org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
				"Dịch vụ tài khoản tạm thời không khả dụng, vui lòng thử lại sau");
	}

	private static ApiException upstreamError() {
		return new ApiException("USER_SERVICE_ERROR", org.springframework.http.HttpStatus.BAD_GATEWAY,
				"Dịch vụ tài khoản gặp lỗi, vui lòng thử lại sau");
	}

	/** Body lỗi chuẩn của dự án {@code { "error": { "code", "message" } }}. */
	record ErrorBody(ErrorDetail error) {
	}

	record ErrorDetail(String code, String message) {
	}

	/**
	 * Hình dạng response {@code GET /api/users/me/addresses/{id}} của user-service.
	 * Chỉ những trường cần cho snapshot đơn hàng được khai — Jackson bỏ qua phần
	 * còn lại.
	 */
	public record AddressView(
			String id,
			String userId,
			String recipientName,
			String phone,
			String province,
			String district,
			String ward,
			String streetAddress) {
	}
}