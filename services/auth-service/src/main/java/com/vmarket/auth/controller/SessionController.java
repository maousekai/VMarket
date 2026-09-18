package com.vmarket.auth.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.auth.dto.ErrorResponse;
import com.vmarket.auth.dto.MessageResponse;
import com.vmarket.auth.dto.SessionSummary;
import com.vmarket.auth.entity.RefreshToken;
import com.vmarket.auth.exception.ApiException;
import com.vmarket.auth.security.RefreshTokenCookieService;
import com.vmarket.auth.service.SessionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * FR-AUTH-06 — Quản lý phiên. Định danh phiên gọi qua cookie HttpOnly
 * {@code refresh_token} (xem {@link SessionService}), KHÔNG qua access token.
 */
@Tag(name = "Auth", description = "Đăng ký, đăng nhập, quản lý phiên")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class SessionController {

	private final SessionService sessionService;
	private final RefreshTokenCookieService refreshCookieService;

	@Operation(summary = "Đăng xuất (FR-AUTH-06)",
			description = "Thu hồi refresh token trong cookie hiện tại và xoá cookie. Idempotent — "
					+ "gọi khi không có/token đã thu hồi vẫn trả 200.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Đã đăng xuất",
					content = @Content(schema = @Schema(implementation = MessageResponse.class))),
	})
	@PostMapping("/logout")
	public MessageResponse logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
		refreshCookieService.read(httpRequest).ifPresent(sessionService::logout);
		refreshCookieService.clear(httpResponse);
		return new MessageResponse("Đã đăng xuất");
	}

	@Operation(summary = "Danh sách phiên đang hoạt động (FR-AUTH-06)",
			description = "Liệt kê mọi thiết bị/phiên còn hiệu lực của user gọi (xác định qua cookie "
					+ "refresh_token hiện tại), mới dùng gần đây trước.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Danh sách phiên",
					content = @Content(schema = @Schema(implementation = SessionSummary.class))),
			@ApiResponse(responseCode = "401", description = "REFRESH_TOKEN_MISSING / SESSION_NOT_FOUND",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@GetMapping("/sessions")
	public List<SessionSummary> listSessions(HttpServletRequest httpRequest) {
		RefreshToken current = sessionService.resolveCurrent(currentRawToken(httpRequest));
		return sessionService.list(current);
	}

	@Operation(summary = "Thu hồi một phiên cụ thể (FR-AUTH-06)",
			description = "Thu hồi phiên theo id, chỉ khi thuộc đúng user gọi. Nếu id trùng phiên hiện "
					+ "tại, cookie refresh_token cũng bị xoá (tương đương đăng xuất).")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Đã thu hồi phiên",
					content = @Content(schema = @Schema(implementation = MessageResponse.class))),
			@ApiResponse(responseCode = "401", description = "REFRESH_TOKEN_MISSING / SESSION_NOT_FOUND",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "TARGET_SESSION_NOT_FOUND",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@DeleteMapping("/sessions/{id}")
	public MessageResponse revokeSession(@PathVariable String id, HttpServletRequest httpRequest,
			HttpServletResponse httpResponse) {
		RefreshToken current = sessionService.resolveCurrent(currentRawToken(httpRequest));
		sessionService.revokeOne(current, id);
		if (current.getId().equals(id)) {
			refreshCookieService.clear(httpResponse);
		}
		return new MessageResponse("Đã thu hồi phiên");
	}

	@Operation(summary = "Thu hồi mọi phiên khác (FR-AUTH-06)",
			description = "Thu hồi mọi phiên khác của user gọi, giữ lại phiên hiện tại.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Đã thu hồi các phiên khác",
					content = @Content(schema = @Schema(implementation = MessageResponse.class))),
			@ApiResponse(responseCode = "401", description = "REFRESH_TOKEN_MISSING / SESSION_NOT_FOUND",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@PostMapping("/sessions/revoke-others")
	public MessageResponse revokeOthers(HttpServletRequest httpRequest) {
		RefreshToken current = sessionService.resolveCurrent(currentRawToken(httpRequest));
		int revoked = sessionService.revokeOthers(current);
		return new MessageResponse("Đã thu hồi " + revoked + " phiên khác");
	}

	private String currentRawToken(HttpServletRequest httpRequest) {
		return refreshCookieService.read(httpRequest)
				.orElseThrow(() -> new ApiException("REFRESH_TOKEN_MISSING", HttpStatus.UNAUTHORIZED,
						"Thiếu cookie refresh_token"));
	}
}
