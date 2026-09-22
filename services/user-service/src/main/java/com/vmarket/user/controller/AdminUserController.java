package com.vmarket.user.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.user.dto.AccountStatus;
import com.vmarket.user.dto.AdminUserResponse;
import com.vmarket.user.dto.ErrorResponse;
import com.vmarket.user.dto.LockUserRequest;
import com.vmarket.user.dto.PageResponse;
import com.vmarket.user.dto.UserActivityResponse;
import com.vmarket.user.service.AdminUserService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * FR-USER-04 — [Admin] Quản lý người dùng: tìm kiếm, xem danh sách, khoá / mở khoá, xem
 * lịch sử hoạt động cơ bản.
 *
 * <p>Mọi endpoint yêu cầu vai trò ADMIN (claim {@code roles} của access token) — kiểm
 * ở cả {@code SecurityConfig} (trước filter Idempotency-Key) lẫn {@code @PreAuthorize} ở
 * đây. Dữ liệu tài khoản, trạng thái khoá và lịch sử do auth-service sở hữu; auth-service
 * còn kiểm tra lại theo CSDL rằng Admin gọi vào vẫn đang hoạt động — xem
 * {@code AdminUserService}.
 */
@Tag(name = "Admin - Users", description = "Quản lý người dùng dành cho Admin (FR-USER-04)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/users")
// Bắt buộc để @Min/@Max/@Pattern trên tham số query/path có hiệu lực: không có
// @Validated thì Spring bỏ qua chúng, và page=-1 sẽ thành lỗi từ phía auth-service.
@Validated
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminUserController {

	/** Chặn trần kích thước trang: không cho client kéo cả bảng về bằng size=1000000. */
	private static final int MAX_PAGE_SIZE = 100;

	/**
	 * userId là ULID 26 ký tự do auth-service cấp. Kiểm tra định dạng ở đây để id rác trả
	 * 400 ngay, không tốn một lượt gọi sang auth-service chỉ để nhận 404.
	 */
	private static final String USER_ID_PATTERN = "^[0-9A-Za-z]{26}$";

	private static final String REVOKED_DESCRIPTION = "Không phải ADMIN (FORBIDDEN), hoặc Admin đã bị khoá / "
			+ "thu hồi vai trò dù access token còn hạn (ADMIN_ACCESS_REVOKED)";

	private final AdminUserService adminUserService;

	@Operation(summary = "Tìm kiếm / liệt kê người dùng (FR-USER-04)",
			description = "Một ô tìm kiếm khớp một phần email, username, họ tên hoặc số điện thoại "
					+ "(không phân biệt hoa thường). Bỏ trống `q` để liệt kê tất cả. Lọc theo `status`. "
					+ "Sắp xếp tài khoản mới tạo trước. Liệt kê cả người chưa từng mở trang hồ sơ "
					+ "(khi đó fullName/phone/avatarUrl = null). Từ khoá khớp hơn 500 hồ sơ theo họ tên / "
					+ "SĐT → 400 SEARCH_TOO_BROAD (không trả kết quả thiếu).")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Thành công",
					content = @Content(schema = @Schema(implementation = PageResponse.class))),
			@ApiResponse(responseCode = "400",
					description = "Tham số không hợp lệ (VALIDATION_ERROR) / từ khoá quá rộng (SEARCH_TOO_BROAD)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "401", description = "Thiếu hoặc sai access token (UNAUTHORIZED)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "403", description = REVOKED_DESCRIPTION,
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "502", description = "auth-service lỗi (AUTH_SERVICE_ERROR)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "503",
					description = "Chưa gọi tới được auth-service (AUTH_SERVICE_UNAVAILABLE)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "504",
					description = "auth-service phản hồi quá chậm, chưa rõ kết quả (AUTH_SERVICE_TIMEOUT) — "
							+ "tải lại danh sách để kiểm tra; thao tác lặp lại được nên thử lại an toàn",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@GetMapping
	public PageResponse<AdminUserResponse> search(@AuthenticationPrincipal String adminId,

			@Parameter(description = "Từ khoá: email, username, họ tên hoặc số điện thoại")
			@RequestParam(required = false) @Size(max = 100) String q,

			@Parameter(description = "ACTIVE = đang hoạt động, LOCKED = bị Admin khoá; bỏ trống = tất cả")
			@RequestParam(required = false) AccountStatus status,

			@Parameter(description = "Trang, đánh số từ 0")
			@RequestParam(defaultValue = "0") @Min(0) int page,

			@Parameter(description = "Số bản ghi mỗi trang, tối đa 100")
			@RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

		return adminUserService.search(q, status, page, size, adminId);
	}

	@Operation(summary = "Xem chi tiết một người dùng (FR-USER-04)")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Thành công",
					content = @Content(schema = @Schema(implementation = AdminUserResponse.class))),
			@ApiResponse(responseCode = "400", description = "userId sai định dạng (VALIDATION_ERROR)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "403", description = REVOKED_DESCRIPTION,
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "Không có tài khoản (USER_NOT_FOUND)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@GetMapping("/{userId}")
	public AdminUserResponse get(@AuthenticationPrincipal String adminId,
			@PathVariable @Pattern(regexp = USER_ID_PATTERN) String userId) {
		return adminUserService.get(userId, adminId);
	}

	@Operation(summary = "Lịch sử hoạt động cơ bản của một người dùng (FR-USER-04)",
			description = "Các sự kiện làm đổi trạng thái tài khoản, mới nhất trước: Admin khoá (kèm Admin thực "
					+ "hiện và lý do) / mở khoá, khoá tạm do nhập sai mật khẩu 5 lần, đổi / đặt lại mật khẩu. "
					+ "Mở khoá không xoá lịch sử các lần khoá trước.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Thành công",
					content = @Content(schema = @Schema(implementation = PageResponse.class))),
			@ApiResponse(responseCode = "400", description = "userId / tham số phân trang sai (VALIDATION_ERROR)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "403", description = REVOKED_DESCRIPTION,
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "Không có tài khoản (USER_NOT_FOUND)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@GetMapping("/{userId}/activities")
	public PageResponse<UserActivityResponse> activities(@AuthenticationPrincipal String adminId,
			@PathVariable @Pattern(regexp = USER_ID_PATTERN) String userId,

			@Parameter(description = "Trang, đánh số từ 0")
			@RequestParam(defaultValue = "0") @Min(0) int page,

			@Parameter(description = "Số bản ghi mỗi trang, tối đa 100")
			@RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

		return adminUserService.activities(userId, page, size, adminId);
	}

	@Operation(summary = "Khoá tài khoản (FR-USER-04)",
			description = "Người bị khoá không đăng nhập / làm mới phiên được nữa (423 ACCOUNT_SUSPENDED); mọi "
					+ "refresh token bị thu hồi ngay. Access token đang còn hạn (≤ 15 phút) vẫn dùng được tới khi "
					+ "hết hạn — nhưng KHÔNG dùng được cho thao tác quản trị (auth-service kiểm tra lại theo CSDL). "
					+ "Gọi lại trên tài khoản đã khoá thì giữ nguyên lần khoá đầu (idempotent).")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Đã khoá",
					content = @Content(schema = @Schema(implementation = AdminUserResponse.class))),
			@ApiResponse(responseCode = "400",
					description = "Thiếu lý do / userId sai định dạng (VALIDATION_ERROR), tự khoá mình (CANNOT_LOCK_SELF)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "403", description = REVOKED_DESCRIPTION,
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "Không có tài khoản (USER_NOT_FOUND)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@PutMapping("/{userId}/lock")
	public AdminUserResponse lock(@AuthenticationPrincipal String adminId,
			@PathVariable @Pattern(regexp = USER_ID_PATTERN) String userId,
			@Valid @RequestBody LockUserRequest request) {
		return adminUserService.lock(userId, request.reason(), adminId);
	}

	@Operation(summary = "Mở khoá tài khoản (FR-USER-04)",
			description = "Gỡ khoá của Admin và cả khoá tạm 15 phút do đăng nhập sai. Idempotent. Admin đang bị "
					+ "khoá không tự mở khoá được bằng access token cũ (403 ADMIN_ACCESS_REVOKED).")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Đã mở khoá",
					content = @Content(schema = @Schema(implementation = AdminUserResponse.class))),
			@ApiResponse(responseCode = "400", description = "userId sai định dạng (VALIDATION_ERROR)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "403", description = REVOKED_DESCRIPTION,
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "Không có tài khoản (USER_NOT_FOUND)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@PutMapping("/{userId}/unlock")
	public AdminUserResponse unlock(@AuthenticationPrincipal String adminId,
			@PathVariable @Pattern(regexp = USER_ID_PATTERN) String userId) {
		return adminUserService.unlock(userId, adminId);
	}
}
