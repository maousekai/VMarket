package com.vmarket.auth.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.auth.dto.ErrorResponse;
import com.vmarket.auth.dto.ForgotPasswordRequest;
import com.vmarket.auth.dto.ForgotPasswordResponse;
import com.vmarket.auth.dto.MessageResponse;
import com.vmarket.auth.dto.ResetPasswordRequest;
import com.vmarket.auth.service.PasswordResetService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Auth", description = "Đăng ký, đăng nhập, quản lý phiên")
@RestController
@RequestMapping("/api/auth/password")
@RequiredArgsConstructor
public class PasswordResetController {

	private final PasswordResetService passwordResetService;

	@Operation(summary = "Yêu cầu đặt lại mật khẩu (FR-AUTH-04)",
			description = "Sinh mã 6 số, gửi qua email. Chặn gửi lại trong 60 giây và giới hạn "
					+ "số mã mỗi giờ cho một tài khoản. Không tiết lộ email đã đăng ký hay chưa.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Đã xử lý yêu cầu (không tiết lộ email tồn tại hay không)",
					content = @Content(schema = @Schema(implementation = ForgotPasswordResponse.class))),
			@ApiResponse(responseCode = "400", description = "Email không hợp lệ (VALIDATION_ERROR)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "429",
					description = "PASSWORD_RESET_TOO_SOON hoặc PASSWORD_RESET_RATE_LIMITED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "502", description = "Không gửi được email (EMAIL_SEND_FAILED)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@PostMapping("/forgot")
	public ForgotPasswordResponse forgot(@Valid @RequestBody ForgotPasswordRequest body) {
		return passwordResetService.forgotPassword(body.email());
	}

	@Operation(summary = "Xác nhận mã và đặt mật khẩu mới (FR-AUTH-04)",
			description = "Đúng mã: băm lại mật khẩu mới, gỡ khoá đăng nhập (nếu có) và thu hồi toàn "
					+ "bộ refresh token hiện có. KHÔNG tự đăng nhập — cần đăng nhập lại bằng mật khẩu mới.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Đặt lại mật khẩu thành công",
					content = @Content(schema = @Schema(implementation = MessageResponse.class))),
			@ApiResponse(responseCode = "400",
					description = "VALIDATION_ERROR / PASSWORD_RESET_NOT_FOUND / PASSWORD_RESET_EXPIRED / "
							+ "PASSWORD_RESET_INVALID / PASSWORD_RESET_TOO_MANY_ATTEMPTS / PASSWORD_RESET_ALREADY_USED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@PostMapping("/reset")
	public MessageResponse reset(@Valid @RequestBody ResetPasswordRequest body) {
		return passwordResetService.resetPassword(body.email(), body.code(), body.newPassword());
	}
}
