package com.vmarket.review.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/** Địa chỉ các service mà Review Service hỏi lại khi kiểm tra quyền (khoá {@code app.review.*}). */
@ConfigurationProperties("app.review")
@Validated
@Getter
@Setter
public class ReviewProperties {

	/** Order Service — xác minh người mua đã nhận hàng trước khi cho đánh giá (FR-REV-01). */
	@NotBlank
	private String orderUrl = "http://localhost:8086";

	/** Shop Service — xác định gian hàng của seller khi phản hồi đánh giá (FR-REV-03). */
	@NotBlank
	private String shopUrl = "http://localhost:8083";
}
