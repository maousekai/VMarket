package com.vmarket.auth.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.auth.dto.ErrorResponse;
import com.vmarket.auth.dto.internal.ChangePasswordRequest;
import com.vmarket.auth.service.PasswordChangeService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * API NỘI BỘ về tài khoản — chỉ cho service khác trong hệ thống gọi (hiện là
 * user-service), xác thực bằng header {@code X-Internal-Api-Key}.
 *
 * <p>Nằm ngoài {@code /api/auth/**} có chủ ý: gateway chỉ định tuyến
 * {@code /api/auth/**} nên client bên ngoài không chạm tới được các path này qua
 * gateway.
 *
 * <p>{@code userId} trên path là do service gọi vào xác định từ access token đã
 * verify — auth-service không tự suy ra "người đang đăng nhập" ở đây vì lời gọi là
 * service-to-service, không mang token của người dùng.
 */
@Tag(name = "Internal - Accounts",
		description = "API nội bộ cho user-service: đổi mật khẩu (FR-USER-03)")
@SecurityRequirement(name = "internalApiKey")
@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class InternalAccountController {

	private final PasswordChangeService passwordChangeService;

	@Operation(summary = "Đổi mật khẩu (FR-USER-03)",
			description = "Kiểm tra mật khẩu hiện tại rồi đặt mật khẩu mới. Nhập sai tính chung bộ đếm "
					+ "với đăng nhập sai (5 lần → khoá 15 phút). Thành công → thu hồi mọi refresh token.")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Đổi thành công"),
			@ApiResponse(responseCode = "400",
					description = "VALIDATION_ERROR / INVALID_CURRENT_PASSWORD / PASSWORD_UNCHANGED / PASSWORD_NOT_SET",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "401", description = "Thiếu/sai X-Internal-Api-Key (UNAUTHORIZED)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "USER_NOT_FOUND",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "423", description = "ACCOUNT_LOCKED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@PutMapping("/{userId}/password")
	public ResponseEntity<Void> changePassword(@PathVariable String userId,
			@Valid @RequestBody ChangePasswordRequest request) {
		passwordChangeService.changePassword(userId, request.currentPassword(), request.newPassword());
		return ResponseEntity.noContent().build();
	}
}
