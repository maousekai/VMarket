package com.vmarket.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.auth.dto.internal.UserContactResponse;
import com.vmarket.auth.exception.ApiException;
import com.vmarket.auth.repository.UserRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * API nội bộ cho notification-service (PBL6-23) tra email người nhận trước khi gửi
 * email giao dịch (FR-NOTI-02). Nằm dưới {@code /internal/**} nên được
 * {@code InternalApiKeyFilter} bảo vệ bằng {@code X-Internal-Api-Key} và gateway
 * không định tuyến tới.
 *
 * <p>Khác {@code GET /internal/users/{userId}} (FR-USER-04) ở chỗ không yêu cầu
 * {@code X-Actor-Id} của Admin: đây là lời gọi máy-với-máy do một sự kiện nghiệp vụ
 * kích hoạt, không có Admin nào thao tác.
 */
@Tag(name = "Internal - Contacts", description = "API nội bộ cho notification-service: tra email người nhận")
@SecurityRequirement(name = "internalApiKey")
@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class InternalContactController {

	private final UserRepository users;

	@Operation(summary = "Thông tin liên hệ của tài khoản (FR-NOTI-02)")
	@GetMapping("/{userId}/contact")
	@Transactional(readOnly = true)
	public UserContactResponse contact(@PathVariable String userId) {
		return users.findById(userId)
				.map(UserContactResponse::from)
				.orElseThrow(() -> new ApiException("USER_NOT_FOUND", HttpStatus.NOT_FOUND, "Không tìm thấy tài khoản"));
	}
}
