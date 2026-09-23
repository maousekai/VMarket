package com.vmarket.order.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Kết nối tới cart-service ({@code app.cart-service.*}).
 *
 * <p>Order-service đọc giỏ hàng qua API {@code /api/cart/**} của cart-service khi
 * đặt hàng (FR-ORDER-01) và xoá giỏ sau khi tạo đơn thành công. Không cần khoá nội
 * bộ: cart-service tin header {@code X-User-Id} do gateway gắn vào, còn
 * order-service đã tự verify JWT nên gửi thẳng {@code userId} đã xác thực vào
 * header đó — nếu đặt sai userId là lỗi của chính mình, không phải lỗ hổng.
 */
@ConfigurationProperties(prefix = "app.cart-service")
@Validated
@Getter
@Setter
public class CartServiceProperties {

	/** Vd {@code http://cart-service:8085} trong mạng Docker, {@code http://localhost:8085} khi chạy mvnw. */
	@NotBlank
	private String baseUrl;

	/** Timeout ngắn có chủ ý: giỏ hàng đọc từ Redis, chậm là có vấn đề — trả 503 sớm. */
	@NotNull
	private Duration connectTimeout = Duration.ofSeconds(2);

	/** Đọc lâu hơn kết nối một chút: giỏ lớn vẫn về kịp trong thời gian bình thường. */
	@NotNull
	private Duration readTimeout = Duration.ofSeconds(5);
}