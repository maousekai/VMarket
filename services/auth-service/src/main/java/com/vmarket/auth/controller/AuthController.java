package com.vmarket.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.auth.dto.RegisterRequest;
import com.vmarket.auth.dto.RegisterResponse;
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

	@Operation(summary = "Đăng ký tài khoản (FR-AUTH-01)",
			description = "Tạo tài khoản mới với vai trò BUYER. Tài khoản ở trạng thái PENDING "
					+ "cho tới khi xác thực email. Chưa trả JWT — dùng /api/auth/login để đăng nhập.")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Đăng ký thành công",
					content = @Content(schema = @Schema(implementation = RegisterResponse.class))),
			@ApiResponse(responseCode = "400",
					description = "Dữ liệu không hợp lệ (VALIDATION_ERROR) hoặc body sai JSON (MALFORMED_REQUEST)",
					content = @Content(schema = @Schema(implementation = com.vmarket.auth.dto.ErrorResponse.class))),
			@ApiResponse(responseCode = "409",
					description = "Trùng email (EMAIL_ALREADY_EXISTS), username (USERNAME_ALREADY_EXISTS), "
							+ "hoặc trùng do race (REGISTRATION_CONFLICT)",
					content = @Content(schema = @Schema(implementation = com.vmarket.auth.dto.ErrorResponse.class))),
			@ApiResponse(responseCode = "500", description = "Lỗi máy chủ (INTERNAL_ERROR)",
					content = @Content(schema = @Schema(implementation = com.vmarket.auth.dto.ErrorResponse.class))),
	})
	@PostMapping("/register")
	public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(registrationService.register(request));
	}
}
