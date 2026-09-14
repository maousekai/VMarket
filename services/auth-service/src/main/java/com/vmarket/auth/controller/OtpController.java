package com.vmarket.auth.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.auth.dto.ErrorResponse;
import com.vmarket.auth.dto.OtpRequestDto;
import com.vmarket.auth.dto.OtpRequestResponse;
import com.vmarket.auth.dto.OtpVerifyDto;
import com.vmarket.auth.dto.TokenResponse;
import com.vmarket.auth.service.OtpService;

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
@RequestMapping("/api/auth/otp")
@RequiredArgsConstructor
public class OtpController {

	private final OtpService otpService;

	@Operation(summary = "Gửi mã OTP xác thực email (FR-AUTH-01)",
			description = "Sinh mã 6 số, gửi qua email. Chặn gửi lại trong 60 giây và giới hạn "
					+ "số mã mỗi giờ cho một email. Không tiết lộ email đã đăng ký hay chưa.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Đã gửi mã (nếu email hợp lệ)",
					content = @Content(schema = @Schema(implementation = OtpRequestResponse.class))),
			@ApiResponse(responseCode = "400", description = "Email không hợp lệ (VALIDATION_ERROR)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "429",
					description = "OTP_RESEND_TOO_SOON hoặc OTP_RATE_LIMITED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "502", description = "Không gửi được email (EMAIL_SEND_FAILED)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@PostMapping("/request")
	public OtpRequestResponse request(@Valid @RequestBody OtpRequestDto body) {
		return otpService.requestOtp(body.email());
	}

	@Operation(summary = "Xác nhận mã OTP (FR-AUTH-01)",
			description = "Đúng mã: đánh dấu email đã xác thực (tạo tài khoản mới không mật khẩu "
					+ "nếu chưa có), trả về access token + refresh token như luồng đăng nhập.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Xác thực thành công",
					content = @Content(schema = @Schema(implementation = TokenResponse.class))),
			@ApiResponse(responseCode = "400",
					description = "VALIDATION_ERROR / OTP_NOT_FOUND / OTP_EXPIRED / OTP_INVALID / "
							+ "OTP_TOO_MANY_ATTEMPTS / OTP_ALREADY_USED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@PostMapping("/verify")
	public TokenResponse verify(@Valid @RequestBody OtpVerifyDto body) {
		return otpService.verifyOtp(body.email(), body.otp());
	}
}
