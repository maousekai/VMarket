package com.vmarket.cart.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Khoá xác thực cuộc gọi NỘI BỘ giữa các service (khoá {@code app.internal-api.*}).
 *
 * <p><b>Vì sao cần.</b> Danh tính người dùng trên đường đi từ browser luôn là JWT
 * đã verify (xem {@code CartAuthenticationFilter}). Nhưng có những lời gọi không
 * đến từ browser: order-service gọi cart-service để đọc/xoá giỏ khi đặt hàng —
 * tiến trình này không có token của người dùng trong tay. Trước đây cart-service
 * tin thẳng header {@code X-User-Id} trong mọi trường hợp, nghĩa là ai gọi được
 * cổng 8085 cũng đọc/sửa được giỏ của người khác chỉ bằng một header tự đặt
 * (P1 của review PR #23).
 *
 * <p>Từ đây, {@code X-User-Id} chỉ được chấp nhận khi đi kèm khoá nội bộ
 * {@code X-Internal-Api-Key} đúng — cùng cơ chế auth-service dùng cho API
 * {@code /internal/**} (PBL6-13). Khoá nằm ở biến môi trường phía server,
 * KHÔNG bao giờ gửi ra frontend.
 */
@ConfigurationProperties(prefix = "app.internal-api")
@Validated
@Getter
@Setter
public class InternalApiProperties {

	/** Header mang khoá nội bộ — khớp {@code InternalApiProperties.HEADER} của auth-service. */
	public static final String HEADER = "X-Internal-Api-Key";

	/** Header mang userId của cuộc gọi nội bộ — gateway strip mọi header {@code X-User-*} từ client. */
	public static final String USER_ID_HEADER = "X-User-Id";

	@NotBlank
	@Size(min = 32, message = "INTERNAL_API_KEY phải >= 32 ký tự")
	private String apiKey;
}