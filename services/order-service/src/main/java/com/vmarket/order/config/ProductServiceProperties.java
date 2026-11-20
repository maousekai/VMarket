package com.vmarket.order.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * product-service: order-service đọc giá hiện tại của từng dòng hàng lúc đặt hàng
 * để không tin giá lưu trong giỏ (PBL6-48).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.product-service")
public class ProductServiceProperties {

	/** Vd {@code http://product-service:8084} trong mạng Docker. */
	private String baseUrl = "http://localhost:8084";

	private Duration connectTimeout = Duration.ofSeconds(2);

	private Duration readTimeout = Duration.ofSeconds(3);
}
