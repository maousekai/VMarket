package com.vmarket.user.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import lombok.Getter;
import lombok.Setter;

/** Khoá cho các endpoint nội bộ {@code /internal/**} của user-service. */
@ConfigurationProperties(prefix = "app.internal")
@Validated
@Getter
@Setter
public class InternalApiProperties {

	public static final String HEADER = "X-Internal-Api-Key";

	@NotBlank
	@Size(min = 32, message = "INTERNAL_API_KEY phải >= 32 ký tự")
	private String apiKey;
}
