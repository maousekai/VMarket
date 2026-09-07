package com.vmarket.user.controller;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.user.dto.ErrorResponse;
import com.vmarket.user.dto.PageResponse;
import com.vmarket.user.dto.ProfileResponse;
import com.vmarket.user.service.UserProfileService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/**
 * Phần "tìm kiếm, xem danh sách người dùng" của FR-USER-04, dành cho Admin.
 *
 * <p><b>Phạm vi có chủ ý:</b> khoá/mở khoá tài khoản <i>không</i> nằm ở đây. Trạng
 * thái khoá thuộc về tài khoản đăng nhập, do auth-service sở hữu trong CSDL
 * {@code vmarket_auth}; user-service chỉ giữ hồ sơ hiển thị. Đặt endpoint khoá ở
 * đây sẽ buộc phải đọc chéo CSDL của service khác, trái nguyên tắc
 * database-per-service của SRS. Việc đó thuộc một ticket của auth-service.
 */
@Tag(name = "Admin - Users", description = "Quản lý người dùng dành cho Admin (FR-USER-04)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/users")
// Bắt buộc để @Min/@Max trên tham số query có hiệu lực: không có @Validated thì
// Spring bỏ qua chúng, và page=-1 sẽ thành lỗi 500 từ PageRequest.of.
@Validated
@RequiredArgsConstructor
public class AdminUserController {

	/** Chặn trần kích thước trang: không cho client kéo cả bảng về bằng size=1000000. */
	private static final int MAX_PAGE_SIZE = 100;

	private final UserProfileService userProfileService;

	@Operation(summary = "Tìm kiếm / liệt kê hồ sơ người dùng (FR-USER-04)",
			description = "Tìm theo họ tên hoặc số điện thoại, không phân biệt hoa thường. "
					+ "Bỏ trống `q` để liệt kê tất cả. Chỉ ADMIN gọi được.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Thành công",
					content = @Content(schema = @Schema(implementation = PageResponse.class))),
			@ApiResponse(responseCode = "401", description = "Thiếu hoặc sai access token (UNAUTHORIZED)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "403", description = "Không phải ADMIN (FORBIDDEN)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@PreAuthorize("hasRole('ADMIN')")
	@GetMapping
	public PageResponse<ProfileResponse> search(

			@Parameter(description = "Từ khoá tìm theo họ tên hoặc số điện thoại")
			@RequestParam(required = false) String q,

			@Parameter(description = "Trang, đánh số từ 0")
			@RequestParam(defaultValue = "0") @Min(0) int page,

			@Parameter(description = "Số bản ghi mỗi trang, tối đa 100")
			@RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

		// Sắp xếp cố định theo createdAt giảm dần: không nhận tham số sort từ client
		// vì tên trường sort đi thẳng vào câu truy vấn, và mỗi tên sai là một lỗi 500.
		var pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE),
				Sort.by(Sort.Direction.DESC, "createdAt"));
		return userProfileService.search(q, pageable);
	}
}
