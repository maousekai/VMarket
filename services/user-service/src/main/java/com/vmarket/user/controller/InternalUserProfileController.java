package com.vmarket.user.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.user.config.InternalApiProperties;
import com.vmarket.user.dto.ProfileBatchRequest;
import com.vmarket.user.dto.UserProfileSummaryResponse;
import com.vmarket.user.exception.ApiException;
import com.vmarket.user.service.UserProfileService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * API nội bộ cho shop-service lấy tên/avatar của chủ shop và người xử lý.
 *
 * <p>Không dùng JWT: request đến từ service khác, xác thực bằng
 * {@code X-Internal-Api-Key}. Endpoint chỉ trả ba trường hiển thị, không trả
 * ngày sinh/giới tính/số điện thoại.
 */
@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class InternalUserProfileController {

	private final UserProfileService userProfileService;
	private final InternalApiProperties properties;

	@PostMapping("/profiles")
	public List<UserProfileSummaryResponse> profiles(
			@RequestHeader(value = InternalApiProperties.HEADER, required = false) String key,
			@Valid @RequestBody ProfileBatchRequest request) {
		authorize(key);
		return userProfileService.summaries(request.userIds());
	}

	private void authorize(String key) {
		if (key == null
				|| !MessageDigest.isEqual(
						properties.getApiKey().getBytes(StandardCharsets.UTF_8),
						key.getBytes(StandardCharsets.UTF_8))) {
			throw new ApiException("UNAUTHORIZED", HttpStatus.UNAUTHORIZED, "Sai khoá nội bộ");
		}
	}
}
