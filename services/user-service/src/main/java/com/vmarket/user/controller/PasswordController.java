package com.vmarket.user.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.user.dto.ChangePasswordRequest;
import com.vmarket.user.dto.ErrorResponse;
import com.vmarket.user.dto.MessageResponse;
import com.vmarket.user.service.PasswordService;

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
 * FR-USER-03 — Đổi mật khẩu của chính người đang đăng nhập.
 *
 * <p>Giống {@code /me} của hồ sơ: không nhận userId từ path hay body — danh tính luôn
 * lấy từ access token đã verify.
 *
 * <p><b>Giới hạn đã biết — access token cũ vẫn dùng được tới khi hết hạn (≤ 15 phút).</b>
 * Đổi mật khẩu thu hồi mọi <i>refresh</i> token, nhưng access token là JWT tự chứa nên
 * không có cách vô hiệu hoá nó mà không tra CSDL ở mỗi request. Lý do đổi mật khẩu
 * thường là nghi bị lộ, nên kẻ đang giữ access token vẫn gọi được API trong khoảng đó.
 * Khắc phục cần {@code token_version} trong claim hoặc denylist theo {@code sub + iat} —
 * để ticket riêng vì đụng tới mọi service đang verify token.
 */
@Tag(name = "User Profile", description = "Hồ sơ cá nhân của người dùng đang đăng nhập")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/users/me/password")
@RequiredArgsConstructor
public class PasswordController {

	private final PasswordService passwordService;

	@Operation(summary = "Đổi mật khẩu (FR-USER-03)",
			description = "Yêu cầu mật khẩu hiện tại. Nhập sai 5 lần liên tiếp (tính chung với đăng nhập sai) "
					+ "→ tài khoản bị khoá tạm 15 phút. Đổi thành công → mọi phiên đăng nhập (refresh token) bị "
					+ "thu hồi: client nên đăng nhập lại bằng mật khẩu mới. Tài khoản tạo qua OTP chưa có mật "
					+ "khẩu phải dùng Quên mật khẩu để đặt lần đầu. LƯU Ý: gặp 504 PASSWORD_CHANGE_UNKNOWN thì "
					+ "KHÔNG gửi lại request — mật khẩu có thể đã đổi, gửi lại bằng mật khẩu cũ chỉ làm tăng "
					+ "bộ đếm khoá.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Đổi thành công",
					content = @Content(schema = @Schema(implementation = MessageResponse.class))),
			@ApiResponse(responseCode = "400",
					description = "VALIDATION_ERROR / INVALID_CURRENT_PASSWORD / PASSWORD_UNCHANGED / PASSWORD_NOT_SET",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "401", description = "Thiếu hoặc sai access token (UNAUTHORIZED)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "423",
					description = "Khoá tạm do nhập sai nhiều lần (ACCOUNT_LOCKED) hoặc bị Admin khoá (ACCOUNT_SUSPENDED)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "503",
					description = "Chưa gọi tới được auth-service (AUTH_SERVICE_UNAVAILABLE) — thao tác chắc "
							+ "chắn chưa chạy, thử lại được",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "504",
					description = "Đã gọi tới auth-service nhưng không rõ kết quả (PASSWORD_CHANGE_UNKNOWN) — "
							+ "ĐỪNG thử lại ngay, hãy đăng nhập bằng mật khẩu MỚI để biết nó đã đổi chưa",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@PutMapping
	public MessageResponse changePassword(@AuthenticationPrincipal String userId,
			@Valid @RequestBody ChangePasswordRequest request) {
		passwordService.changePassword(userId, request);
		return new MessageResponse("Đổi mật khẩu thành công. Vui lòng đăng nhập lại bằng mật khẩu mới");
	}
}
