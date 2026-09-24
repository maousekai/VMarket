package com.vmarket.shop.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.shop.dto.ErrorResponse;
import com.vmarket.shop.dto.PageResponse;
import com.vmarket.shop.dto.ReasonRequest;
import com.vmarket.shop.dto.ShopProfileChangeResponse;
import com.vmarket.shop.dto.ShopResponse;
import com.vmarket.shop.dto.ShopStatusHistoryResponse;
import com.vmarket.shop.entity.ShopStatus;
import com.vmarket.shop.service.ShopModerationService;

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
 * FR-SHOP-04 — [Admin] Duyệt gian hàng.
 *
 * <p>Vai trò ADMIN được kiểm ở {@code SecurityConfig} cho cả nhánh
 * {@code /api/shops/admin/**}. Nằm dưới {@code /api/shops} (không phải
 * {@code /api/admin/shops}) vì API Gateway định tuyến theo tiền tố {@code /api/shops/**}.
 *
 * <p>Mỗi quyết định là một endpoint riêng thay vì một {@code PATCH status} chung: mỗi
 * cái có tiền điều kiện riêng (xem {@code ShopAction}), cái cần lý do thì body bắt buộc
 * có lý do, và tài liệu API nói rõ được từng cái phát sự kiện gì.
 */
@Tag(name = "Shops - Admin", description = "Duyệt / từ chối / đình chỉ gian hàng (FR-SHOP-04)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/shops/admin")
// Bắt buộc để @Min/@Max/@Pattern trên tham số query/path có hiệu lực.
@Validated
@RequiredArgsConstructor
public class AdminShopController {

	/** Chặn trần kích thước trang: không cho kéo cả bảng về bằng size=1000000. */
	private static final int MAX_PAGE_SIZE = 100;

	private final ShopModerationService moderationService;

	@Operation(summary = "Danh sách gian hàng",
			description = "Lọc theo `status` (vd PENDING cho hàng đợi chờ duyệt) và `keyword` (khớp một phần tên, "
					+ "không phân biệt hoa thường). Mới nộp trước.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Thành công",
					content = @Content(schema = @Schema(implementation = PageResponse.class))),
			@ApiResponse(responseCode = "400", description = "Tham số không hợp lệ (VALIDATION_ERROR)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "401", description = "Chưa đăng nhập (UNAUTHORIZED)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "403", description = "Không phải ADMIN (FORBIDDEN)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@GetMapping
	public PageResponse<ShopResponse> search(
			@Parameter(description = "Lọc theo trạng thái; bỏ trống = tất cả")
			@RequestParam(required = false) ShopStatus status,
			@Parameter(description = "Khớp một phần tên gian hàng")
			@RequestParam(required = false) @Size(max = 100, message = "Từ khoá tối đa 100 ký tự") String keyword,
			@RequestParam(defaultValue = "0") @Min(value = 0, message = "page phải >= 0") int page,
			@RequestParam(defaultValue = "20") @Min(value = 1, message = "size phải >= 1")
			@Max(value = MAX_PAGE_SIZE, message = "size tối đa 100") int size) {
		return moderationService.search(status, keyword, page, size);
	}

	@Operation(summary = "Chi tiết hồ sơ gian hàng", description = "Mọi trạng thái, kèm thông tin chủ gian hàng.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Thành công",
					content = @Content(schema = @Schema(implementation = ShopResponse.class))),
			@ApiResponse(responseCode = "404", description = "Không tìm thấy gian hàng (SHOP_NOT_FOUND)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@GetMapping("/{shopId}")
	public ShopResponse get(@PathVariable @Pattern(regexp = ShopIds.PATTERN, message = ShopIds.MESSAGE) String shopId) {
		return moderationService.get(shopId);
	}

	@Operation(summary = "Lịch sử trạng thái của gian hàng")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Thành công"),
			@ApiResponse(responseCode = "404", description = "Không tìm thấy gian hàng (SHOP_NOT_FOUND)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@GetMapping("/{shopId}/status-history")
	public List<ShopStatusHistoryResponse> history(
			@PathVariable @Pattern(regexp = ShopIds.PATTERN, message = ShopIds.MESSAGE) String shopId) {
		return moderationService.history(shopId);
	}

	@Operation(summary = "Nhật ký sửa nội dung hồ sơ",
			description = "Người bán sửa được hồ sơ kể cả khi gian hàng đang hoạt động và KHÔNG phải duyệt lại "
					+ "(FR-SHOP-02). Đây là chỗ đối chiếu nội dung hiện tại với nội dung đã duyệt: mỗi trường bị "
					+ "đổi là một dòng, kèm giá trị cũ / mới và trạng thái gian hàng lúc sửa. Mới nhất trước.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Thành công",
					content = @Content(schema = @Schema(implementation = PageResponse.class))),
			@ApiResponse(responseCode = "400", description = "Tham số không hợp lệ (VALIDATION_ERROR)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "Không tìm thấy gian hàng (SHOP_NOT_FOUND)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@GetMapping("/{shopId}/profile-history")
	public PageResponse<ShopProfileChangeResponse> profileHistory(
			@PathVariable @Pattern(regexp = ShopIds.PATTERN, message = ShopIds.MESSAGE) String shopId,
			@RequestParam(defaultValue = "0") @Min(value = 0, message = "page phải >= 0") int page,
			@RequestParam(defaultValue = "20") @Min(value = 1, message = "size phải >= 1")
			@Max(value = MAX_PAGE_SIZE, message = "size tối đa 100") int size) {
		return moderationService.profileHistory(shopId, page, size);
	}

	@Operation(summary = "Duyệt gian hàng",
			description = "PENDING → ACTIVE. Phát sự kiện **ShopApproved** (reinstated=false) sau khi lưu: "
					+ "Auth cấp vai trò SELLER, Notification báo cho người đăng ký.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Đã duyệt",
					content = @Content(schema = @Schema(implementation = ShopResponse.class))),
			@ApiResponse(responseCode = "404", description = "Không tìm thấy gian hàng (SHOP_NOT_FOUND)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "409", description = "Không ở trạng thái chờ duyệt "
					+ "(INVALID_STATUS_TRANSITION) hoặc vừa bị sửa song song (CONCURRENT_MODIFICATION)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@PostMapping("/{shopId}/approve")
	public ShopResponse approve(@AuthenticationPrincipal String adminId,
			@PathVariable @Pattern(regexp = ShopIds.PATTERN, message = ShopIds.MESSAGE) String shopId) {
		return moderationService.approve(adminId, shopId);
	}

	@Operation(summary = "Từ chối hồ sơ (kèm lý do)",
			description = "PENDING → REJECTED. Người bán thấy lý do, sửa hồ sơ rồi gửi lại được.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Đã từ chối",
					content = @Content(schema = @Schema(implementation = ShopResponse.class))),
			@ApiResponse(responseCode = "400", description = "Thiếu lý do (VALIDATION_ERROR)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "Không tìm thấy gian hàng (SHOP_NOT_FOUND)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "409", description = "Không ở trạng thái chờ duyệt "
					+ "(INVALID_STATUS_TRANSITION) hoặc vừa bị sửa song song (CONCURRENT_MODIFICATION)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@PostMapping("/{shopId}/reject")
	public ShopResponse reject(@AuthenticationPrincipal String adminId,
			@PathVariable @Pattern(regexp = ShopIds.PATTERN, message = ShopIds.MESSAGE) String shopId,
			@Valid @RequestBody ReasonRequest request) {
		return moderationService.reject(adminId, shopId, request.reason());
	}

	@Operation(summary = "Đình chỉ gian hàng (kèm lý do)",
			description = "ACTIVE → SUSPENDED. Phát sự kiện **ShopSuspended**: Product ẩn toàn bộ sản phẩm của "
					+ "gian hàng, Notification báo cho chủ gian hàng.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Đã đình chỉ",
					content = @Content(schema = @Schema(implementation = ShopResponse.class))),
			@ApiResponse(responseCode = "400", description = "Thiếu lý do (VALIDATION_ERROR)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "Không tìm thấy gian hàng (SHOP_NOT_FOUND)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "409", description = "Không đang hoạt động "
					+ "(INVALID_STATUS_TRANSITION) hoặc vừa bị sửa song song (CONCURRENT_MODIFICATION)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@PostMapping("/{shopId}/suspend")
	public ShopResponse suspend(@AuthenticationPrincipal String adminId,
			@PathVariable @Pattern(regexp = ShopIds.PATTERN, message = ShopIds.MESSAGE) String shopId,
			@Valid @RequestBody ReasonRequest request) {
		return moderationService.suspend(adminId, shopId, request.reason());
	}

	@Operation(summary = "Gỡ đình chỉ",
			description = "SUSPENDED → ACTIVE. Phát lại **ShopApproved** với reinstated=true để Product hiện lại "
					+ "sản phẩm.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Đã gỡ đình chỉ",
					content = @Content(schema = @Schema(implementation = ShopResponse.class))),
			@ApiResponse(responseCode = "404", description = "Không tìm thấy gian hàng (SHOP_NOT_FOUND)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "409", description = "Không đang bị đình chỉ "
					+ "(INVALID_STATUS_TRANSITION) hoặc vừa bị sửa song song (CONCURRENT_MODIFICATION)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@PostMapping("/{shopId}/reinstate")
	public ShopResponse reinstate(@AuthenticationPrincipal String adminId,
			@PathVariable @Pattern(regexp = ShopIds.PATTERN, message = ShopIds.MESSAGE) String shopId) {
		return moderationService.reinstate(adminId, shopId);
	}
}
