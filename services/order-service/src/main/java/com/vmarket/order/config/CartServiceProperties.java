package com.vmarket.order.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Kết nối tới cart-service ({@code app.cart-service.*}).
 *
 * <p>Order-service đọc giỏ hàng qua API {@code /api/cart/**} của cart-service khi
 * đặt hàng (FR-ORDER-01) và xoá giỏ sau khi tạo đơn thành công.
 *
 * <p><b>Cần khoá nội bộ.</b> Từ PBL6-16 (soát xét PR #23), cart-service TỰ verify
 * JWT và chỉ tin {@code X-User-Id} khi request kèm khoá nội bộ
 * {@code X-Internal-Api-Key} — trước đó ai gọi được cổng 8085 cũng mạo danh được
 * người khác bằng một header tự đặt. Cuộc gọi từ order-service đi theo đường thứ
 * hai đó: userId là {@code sub} của token order-service đã verify, và khoá nội bộ
 * chứng minh request đến từ một service trong hệ thống.
 */
@ConfigurationProperties(prefix = "app.cart-service")
@Validated
@Getter
@Setter
public class CartServiceProperties {

	/** Header mang khoá nội bộ — khớp {@code InternalApiProperties.HEADER} của cart-service. */
	public static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

	/** Vd {@code http://cart-service:8085} trong mạng Docker, {@code http://localhost:8085} khi chạy mvnw. */
	@NotBlank
	private String baseUrl;

	/** Dùng CHUNG giá trị {@code INTERNAL_API_KEY} với cart-service (và các service khác). */
	@NotBlank
	@Size(min = 32, message = "INTERNAL_API_KEY phải >= 32 ký tự")
	private String internalApiKey;

	/** Timeout ngắn có chủ ý: giỏ hàng đọc từ Redis, chậm là có vấn đề — trả 503 sớm. */
	@NotNull
	private Duration connectTimeout = Duration.ofSeconds(2);

	/** Đọc lâu hơn kết nối một chút: giỏ lớn vẫn về kịp trong thời gian bình thường. */
	@NotNull
	private Duration readTimeout = Duration.ofSeconds(5);
}