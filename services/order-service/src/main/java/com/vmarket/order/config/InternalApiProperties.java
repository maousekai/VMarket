package com.vmarket.order.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import lombok.Getter;
import lombok.Setter;

/**
 * Khoá dùng chung cho các endpoint {@code /internal/**} của order-service.
 *
 * <p>Tách khỏi {@link DeliveryIntegrationProperties} để endpoint thống kê cho
 * shop-service không phải mượn cấu hình của luồng giao hàng, dù cả hai dùng
 * cùng một giá trị {@code INTERNAL_API_KEY}.
 */
@ConfigurationProperties(prefix = "app.internal")
@Validated
@Getter
@Setter
public class InternalApiProperties {

	@NotBlank
	@Size(min = 32)
	private String apiKey;
}
