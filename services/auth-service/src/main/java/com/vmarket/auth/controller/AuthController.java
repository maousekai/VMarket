package com.vmarket.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.auth.dto.ErrorResponse;
import com.vmarket.auth.dto.LoginRequest;
import com.vmarket.auth.dto.RefreshRequest;
import com.vmarket.auth.dto.RegisterRequest;
import com.vmarket.auth.dto.RegisterResponse;
import com.vmarket.auth.dto.TokenResponse;
import com.vmarket.auth.service.AuthenticationService;
import com.vmarket.auth.service.RegistrationService;

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
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

	private final RegistrationService registrationService;
	private final AuthenticationService authenticationService;

	@Operation(summary = "Đăng ký tài khoản (FR-AUTH-01)",
			description = "Tạo tài khoản mới với vai trò BUYER. Tài khoản ở trạng thái PENDING "
					+ "cho tới khi xác thực email. Chưa trả JWT — dùng /api/auth/login để đăng nhập.")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Đăng ký thành công",
					content = @Content(schema = @Schema(implementation = RegisterResponse.class))),
			@ApiResponse(responseCode = "400",
					description = "Dữ liệu không hợp lệ (VALIDATION_ERROR) hoặc body sai JSON (MALFORMED_REQUEST)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "409",
					description = "Trùng email (EMAIL_ALREADY_EXISTS), username (USERNAME_ALREADY_EXISTS), "
							+ "hoặc trùng do race (REGISTRATION_CONFLICT)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "500", description = "Lỗi máy chủ (INTERNAL_ERROR)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@PostMapping("/register")
	public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(registrationService.register(request));
	}

	@Operation(summary = "Đăng nhập (FR-AUTH-02)",
			description = "Xác thực email + mật khẩu, trả về access token (JWT) + refresh token. "
					+ "Sai 5 lần liên tiếp → khoá tài khoản 15 phút. User chưa xác thực email vẫn "
					+ "đăng nhập được (status = PENDING).")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Đăng nhập thành công",
					content = @Content(schema = @Schema(implementation = TokenResponse.class))),
			@ApiResponse(responseCode = "400", description = "Dữ liệu không hợp lệ (VALIDATION_ERROR)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "401", description = "Sai email/mật khẩu (INVALID_CREDENTIALS)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "423", description = "Tài khoản đang bị khoá tạm (ACCOUNT_LOCKED)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@PostMapping("/login")
	public TokenResponse login(@Valid @RequestBody LoginRequest request) {
		return authenticationService.login(request);
	}

	@Operation(summary = "Làm mới access token (FR-AUTH-02)",
			description = "Dùng refresh token để lấy cặp token mới (xoay vòng). Refresh token cũ "
					+ "bị thu hồi. Dùng lại token đã thu hồi → toàn bộ phiên của user bị thu hồi.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Cấp token mới thành công",
					content = @Content(schema = @Schema(implementation = TokenResponse.class))),
			@ApiResponse(responseCode = "401",
					description = "INVALID_REFRESH_TOKEN / REFRESH_TOKEN_EXPIRED / REFRESH_TOKEN_REUSED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@PostMapping("/refresh")
	public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
		return authenticationService.refresh(request);
	}
}
