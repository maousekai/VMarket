package com.vmarket.shop.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Cấu hình gọi các service khác cho dashboard và enrich dữ liệu hiển thị.
 *
 * <p>Không có giá trị mặc định cho {@code internalApiKey}: thiếu
 * {@code INTERNAL_API_KEY} thì shop-service fail ngay lúc khởi động, thay vì chạy
 * được rồi trả 401 khó hiểu khi Admin mở danh sách gian hàng.
 */
@ConfigurationProperties(prefix = "app.downstream")
@Validated
@Getter
@Setter
public class DownstreamProperties {

	public static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

	@NotBlank
	private String userServiceUrl;

	@NotBlank
	private String orderServiceUrl;

	@NotBlank
	private String productServiceUrl;

	@NotBlank
	@Size(min = 32)
	private String internalApiKey;

	@NotNull
	private Duration connectTimeout = Duration.ofSeconds(2);

	@NotNull
	private Duration readTimeout = Duration.ofSeconds(5);
}
