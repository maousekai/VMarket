package com.vmarket.product.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.vmarket.product.exception.ApiException;

@Component
public class InternalApiKeyGuard {
	private final byte[] expectedKey;

	public InternalApiKeyGuard(@Value("${app.internal-api-key}") String expectedKey) {
		this.expectedKey = expectedKey.getBytes(StandardCharsets.UTF_8);
	}

	public void requireValid(String suppliedKey) {
		byte[] supplied = suppliedKey == null ? new byte[0] : suppliedKey.getBytes(StandardCharsets.UTF_8);
		if (expectedKey.length == 0 || !MessageDigest.isEqual(expectedKey, supplied)) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_INTERNAL_API_KEY", "Thông tin xác thực nội bộ không hợp lệ");
		}
	}
}
