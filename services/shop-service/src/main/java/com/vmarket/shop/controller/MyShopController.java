package com.vmarket.shop.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.shop.dto.ErrorResponse;
import com.vmarket.shop.dto.ShopRequest;
import com.vmarket.shop.dto.ShopResponse;
import com.vmarket.shop.dto.ShopStatusHistoryResponse;
import com.vmarket.shop.service.ShopService;

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
 * Đăng ký gian hàng (FR-SHOP-01) và gian hàng của người đang đăng nhập (FR-SHOP-02).
 *
 * <p>Mọi endpoint thao tác trong phạm vi {@code /me} — chủ gian hàng lấy từ token,
 * không bao giờ từ path hay body.
 */
@Tag(name = "Shops - Seller", description = "Đăng ký và quản lý gian hàng của tôi (FR-SHOP-01, FR-SHOP-02)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/shops")
@RequiredArgsConstructor
public class MyShopController {

	private final ShopService shopService;

	@Operation(summary = "Đăng ký mở gian hàng (FR-SHOP-01)",
			description = "Cần vai trò BUYER. Hồ sơ vào trạng thái PENDING (Chờ duyệt) cho tới khi Admin duyệt. "
					+ "Mỗi tài khoản một gian hàng; tên gian hàng không trùng (không phân biệt hoa thường).")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Đã nộp hồ sơ",
					content = @Content(schema = @Schema(implementation = ShopResponse.class))),
			@ApiResponse(responseCode = "400", description = "Dữ liệu không hợp lệ (VALIDATION_ERROR)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "401", description = "Chưa đăng nhập (UNAUTHORIZED)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "403", description = "Không có vai trò BUYER (FORBIDDEN)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "409",
					description = "Đã có gian hàng (SHOP_ALREADY_EXISTS) hoặc trùng tên (SHOP_NAME_TAKEN)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@PostMapping
	public ResponseEntity<ShopResponse> register(@AuthenticationPrincipal String userId,
			@Valid @RequestBody ShopRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(shopService.register(userId, request));
	}

	@Operation(summary = "Gian hàng của tôi",
			description = "Mọi trạng thái, kèm lý do nếu đang bị từ chối / đình chỉ.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Thành công",
					content = @Content(schema = @Schema(implementation = ShopResponse.class))),
			@ApiResponse(responseCode = "404", description = "Chưa đăng ký gian hàng (SHOP_NOT_FOUND)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@GetMapping("/me")
	public ShopResponse getMine(@AuthenticationPrincipal String userId) {
		return shopService.getMine(userId);
	}

	@Operation(summary = "Cập nhật thông tin gian hàng (FR-SHOP-02)",
			description = "THAY THẾ TOÀN BỘ hồ sơ: trường tuỳ chọn không gửi sẽ bị xoá. Được sửa khi PENDING, "
					+ "REJECTED, ACTIVE; không đổi trạng thái (sửa gian hàng đang hoạt động không phải chờ duyệt lại).")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Đã cập nhật",
					content = @Content(schema = @Schema(implementation = ShopResponse.class))),
			@ApiResponse(responseCode = "400", description = "Dữ liệu không hợp lệ (VALIDATION_ERROR)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "Chưa đăng ký gian hàng (SHOP_NOT_FOUND)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "409",
					description = "Trùng tên (SHOP_NAME_TAKEN), đang bị đình chỉ (SHOP_SUSPENDED) hoặc vừa bị "
							+ "thao tác khác sửa (CONCURRENT_MODIFICATION)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@PutMapping("/me")
	public ShopResponse updateMine(@AuthenticationPrincipal String userId,
			@Valid @RequestBody ShopRequest request) {
		return shopService.updateMine(userId, request);
	}

	@Operation(summary = "Gửi lại hồ sơ bị từ chối",
			description = "REJECTED → PENDING. Sửa hồ sơ bằng PUT /api/shops/me trước rồi gửi lại.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Đã gửi lại, chờ duyệt",
					content = @Content(schema = @Schema(implementation = ShopResponse.class))),
			@ApiResponse(responseCode = "404", description = "Chưa đăng ký gian hàng (SHOP_NOT_FOUND)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "409", description = "Hồ sơ không ở trạng thái bị từ chối "
					+ "(INVALID_STATUS_TRANSITION)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@PostMapping("/me/resubmit")
	public ShopResponse resubmit(@AuthenticationPrincipal String userId) {
		return shopService.resubmit(userId);
	}

	@Operation(summary = "Lịch sử trạng thái gian hàng của tôi",
			description = "Các lần nộp, duyệt, từ chối, đình chỉ... theo thứ tự thời gian (cũ trước).")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Thành công"),
			@ApiResponse(responseCode = "404", description = "Chưa đăng ký gian hàng (SHOP_NOT_FOUND)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@GetMapping("/me/status-history")
	public List<ShopStatusHistoryResponse> myHistory(@AuthenticationPrincipal String userId) {
		return shopService.myHistory(userId);
	}
}
