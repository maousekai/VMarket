package com.vmarket.order.client;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.vmarket.order.exception.ApiException;

import lombok.extern.slf4j.Slf4j;

/**
 * Gọi API {@code /api/cart/**} của cart-service khi đặt hàng (FR-ORDER-01).
 *
 * <p><b>Danh tính:</b> từ PBL6-16 (soát xét PR #23), cart-service TỰ verify JWT và
 * chỉ tin header {@code X-User-Id} khi request kèm khoá nội bộ
 * {@code X-Internal-Api-Key} (xem {@code CartServiceProperties}). Order-service đã
 * tự verify token (NFR-SEC-03) nên gửi {@code userId} là claim {@code sub} của
 * token đã kiểm chữ ký — không phải giá trị client tự khai. Khoá nội bộ được gắn
 * một lần ở {@code DownstreamClientConfig} cho mọi request của client này.
 *
 * <p><b>Ánh xạ lỗi</b> — cùng triết lý với {@code AuthServiceClient} của
 * user-service:
 * <ul>
 *   <li>cart-service trả 4xx nghiệp vụ → chuyển tiếp mã + status + thông báo
 *       (đó là câu trả lời đúng cho người dùng).</li>
 *   <li>cart-service 5xx / body không đọc được → 502 {@code CART_SERVICE_ERROR}.</li>
 *   <li><b>Chưa tới được cart-service</b> (connection refused, sai host, hết thời
 *       gian <i>kết nối</i>) → 503 {@code CART_SERVICE_UNAVAILABLE} — request chắc
 *       chắn chưa chạy, bảo người dùng thử lại là đúng.</li>
 *   <li><b>Đã tới nơi nhưng không rõ kết quả</b> (hết thời gian <i>đọc</i>) → mã
 *       riêng do từng lời gọi quyết định. Với <i>đọc giỏ</i> thì đọc là thao tác vô
 *       hại nên gộp vào 503 thử lại được; với <i>xoá giỏ</i> thì KHÔNG ném — đơn có
 *       thể đã tạo, báo "thử lại" sẽ khiến người dùng đặt đơn thứ hai.</li>
 * </ul>
 *
 * <p><b>Phân loại theo kiểu exception, không dò chuỗi thông báo.</b> Chỉ những lỗi
 * <i>chắc chắn</i> chưa gửi được byte nào mới vào nhóm "chưa tới nơi".
 */
@Slf4j
public class CartServiceClient {

	/**
	 * Khớp {@code InternalApiProperties.USER_ID_HEADER} của cart-service — chỉ có
	 * hiệu lực khi request kèm khoá nội bộ {@code X-Internal-Api-Key}.
	 */
	public static final String HEADER_USER_ID = "X-User-Id";

	private final RestClient restClient;

	public CartServiceClient(RestClient restClient) {
		this.restClient = restClient;
	}

	/**
	 * Đọc giỏ hàng hiện có của người dùng.
	 *
	 * @return giỏ hàng; có thể rỗng ({@code groups} trống) — quyền quyết định "có
	 *         đủ điều kiện đặt" thuộc {@code OrderService}
	 * @throws ApiException 503 {@code CART_SERVICE_UNAVAILABLE} khi chưa tới được
	 * @throws ApiException 502 {@code CART_SERVICE_ERROR} khi cart-service hỏng
	 */
	public CartView getCart(String userId) {
		try {
			CartView cart = restClient.get()
					.uri("/api/cart")
					.header(HEADER_USER_ID, userId)
					.retrieve()
					.body(CartView.class);
			if (cart == null) {
				log.error("cart-service trả body rỗng cho GET /api/cart");
				throw upstreamError();
			}
			return cart;
		} catch (RestClientResponseException ex) {
			throw translate(ex);
		} catch (ResourceAccessException ex) {
			if (neverReachedCartService(ex)) {
				log.error("Không kết nối được cart-service: {}", ex.getMessage());
				throw unavailable();
			}
			// Đọc giỏ là thao tác vô hại (không ghi dữ liệu) — không rõ kết quả cũng
			// chỉ là "chưa đọc được", thử lại an toàn.
			log.error("Mất kết nối với cart-service khi đọc giỏ: {}", ex.getMessage());
			throw unavailable();
		} catch (RestClientException ex) {
			log.error("Response không hợp lệ từ cart-service: {}", ex.getMessage());
			throw upstreamError();
		}
	}

	/**
	 * Xoá toàn bộ giỏ sau khi tạo đơn thành công (FR-ORDER-01).
	 *
	 * <p><b>Chạy SAU khi đơn đã commit.</b> Xoá hỏng không được phép làm hỏng đơn:
	 * {@code OrderService} chỉ log cảnh báo, người dùng xoá tay vài item còn lại.
	 * Vì vậy phương thức này trả {@code boolean} thay vì ném — kể cả read timeout
	 * (có thể cart-service đã xoá xong mà response chưa về) cũng chỉ log, không báo
	 * "thất bại" chắc nịch; sai lệch này vô hại (giỏ còn, đơn không đôi).
	 *
	 * @return {@code true} nếu cart-service xác nhận đã xoá (2xx)
	 */
	public boolean clearCart(String userId) {
		try {
			restClient.delete()
					.uri("/api/cart")
					.header(HEADER_USER_ID, userId)
					.retrieve()
					.toBodilessEntity();
			return true;
		} catch (RestClientResponseException ex) {
			if (ex.getStatusCode().is5xxServerError()) {
				log.warn("Xoá giỏ thất bại (cart-service HTTP {}) — đơn vẫn giữ nguyên, "
						+ "người dùng có thể tự xoá item", ex.getStatusCode().value());
			} else {
				log.error("cart-service từ chối xoá giỏ (HTTP {}) cho userId={}",
						ex.getStatusCode().value(), userId);
			}
			return false;
		} catch (RestClientException ex) {
			log.warn("Không chắc đã xoá giỏ cho userId={}: {}", userId, ex.getClass().getSimpleName());
			return false;
		}
	}

	/**
	 * Chỉ {@code true} khi chắc chắn chưa byte nào tới cart-service.
	 *
	 * <p>Mặc định trả {@code false} (coi như không rõ) cho mọi lỗi lạ: nói "chắc
	 * chắn hỏng" trong khi thao tác đã chạy xong là kiểu đoán nhầm gây hại.
	 */
	private static boolean neverReachedCartService(Throwable ex) {
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

	/** Chuyển tiếp lỗi nghiệp vụ 4xx của cart-service; còn lại gộp thành lỗi hạ tầng. */
	private ApiException translate(RestClientResponseException ex) {
		HttpStatusCode status = ex.getStatusCode();
		if (status.is5xxServerError()) {
			log.error("cart-service trả lỗi HTTP {}", status.value());
			return unavailable();
		}
		HttpStatus known = HttpStatus.resolve(status.value());
		if (known != null && known.is4xxClientError()) {
			ErrorBody body = readErrorBody(ex);
			if (body != null && body.error() != null && body.error().code() != null) {
				return new ApiException(body.error().code(), known, body.error().message());
			}
		}
		log.error("cart-service trả lỗi không mong đợi HTTP {}", status.value());
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
		return new ApiException("CART_SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
				"Dịch vụ giỏ hàng tạm thời không khả dụng, vui lòng thử lại sau");
	}

	private static ApiException upstreamError() {
		return new ApiException("CART_SERVICE_ERROR", HttpStatus.BAD_GATEWAY,
				"Dịch vụ giỏ hàng gặp lỗi, vui lòng thử lại sau");
	}

	/** Body lỗi chuẩn của dự án {@code { "error": { "code", "message" } }}. */
	record ErrorBody(ErrorDetail error) {
	}

	record ErrorDetail(String code, String message) {
	}

	/** Hình dạng response {@code GET /api/cart} của cart-service — nhóm theo shop. */
	public record CartView(
			String userId,
			List<CartGroupView> groups,
			int totalQuantity,
			BigDecimal totalAmount) {
	}

	public record CartGroupView(
			String shopId,
			List<CartItemView> items,
			BigDecimal subtotal) {
	}

	public record CartItemView(
			String productId,
			String variantId,
			String shopId,
			int quantity,
			BigDecimal unitPrice,
			BigDecimal lineTotal) {
	}
}