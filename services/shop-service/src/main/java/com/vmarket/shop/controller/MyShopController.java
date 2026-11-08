package com.vmarket.shop.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.vmarket.media.MediaUploadResponse;
import com.vmarket.shop.dto.ErrorResponse;
import com.vmarket.shop.dto.ShopRecentOrderResponse;
import com.vmarket.shop.dto.ShopRequest;
import com.vmarket.shop.dto.ShopResponse;
import com.vmarket.shop.dto.ShopStatsResponse;
import com.vmarket.shop.dto.ShopStatusHistoryResponse;
import com.vmarket.shop.service.ShopService;
import com.vmarket.shop.service.ShopMediaService;
import com.vmarket.shop.service.ShopStatsService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
@Validated
@RequiredArgsConstructor
public class MyShopController {

	private final ShopService shopService;
	private final ShopStatsService shopStatsService;
	private final ShopMediaService shopMediaService;

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

	@Operation(summary = "Tải logo gian hàng lên kho ảnh",
			description = "Nhận JPEG/PNG/WebP tối đa 2 MB, tự cắt vuông 512×512 và trả URL công khai. "
					+ "Dùng được trước khi đăng ký gian hàng; endpoint KHÔNG ghi vào hồ sơ.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Đã tải lên",
					content = @Content(schema = @Schema(implementation = MediaUploadResponse.class))),
			@ApiResponse(responseCode = "400", description = "Thiếu file, quá dung lượng hoặc sai định dạng (MEDIA_*)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "503", description = "Kho ảnh không khả dụng "
					+ "(MEDIA_STORAGE_UNAVAILABLE)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@PostMapping(value = "/me/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public MediaUploadResponse uploadLogo(@AuthenticationPrincipal String userId,
			@RequestPart("file") MultipartFile file) {
		return shopMediaService.uploadLogo(userId, file);
	}

	@Operation(summary = "Tải ảnh bìa gian hàng lên kho ảnh",
			description = "Nhận JPEG/PNG/WebP tối đa 5 MB, thu nhỏ vừa 1600×900 và trả URL công khai. "
					+ "Dùng được trước khi đăng ký gian hàng; endpoint KHÔNG ghi vào hồ sơ.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Đã tải lên",
					content = @Content(schema = @Schema(implementation = MediaUploadResponse.class))),
			@ApiResponse(responseCode = "400", description = "Thiếu file, quá dung lượng hoặc sai định dạng (MEDIA_*)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "503", description = "Kho ảnh không khả dụng "
					+ "(MEDIA_STORAGE_UNAVAILABLE)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@PostMapping(value = "/me/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public MediaUploadResponse uploadCover(@AuthenticationPrincipal String userId,
			@RequestPart("file") MultipartFile file) {
		return shopMediaService.uploadCover(userId, file);
	}

	@Operation(summary = "Xoá ảnh gian hàng vừa tải lên",
			description = "Chỉ xoá được ảnh thuộc thư mục shops/{userId} của chính người dùng. "
					+ "Dùng để dọn ảnh khi lưu hồ sơ thất bại. Ảnh đang là logo hoặc ảnh bìa "
					+ "của hồ sơ sẽ bị từ chối.")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Đã xoá (hoặc ảnh không còn)"),
			@ApiResponse(responseCode = "400", description = "URL không hợp lệ (INVALID_MEDIA_URL)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "409", description = "Ảnh đang được hồ sơ tham chiếu "
					+ "(MEDIA_IN_USE)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "503", description = "Kho ảnh không khả dụng "
					+ "(MEDIA_STORAGE_UNAVAILABLE)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@DeleteMapping("/me/media")
	public ResponseEntity<Void> deleteMedia(@AuthenticationPrincipal String userId,
			@RequestParam String url) {
		shopMediaService.deleteMedia(userId, url);
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
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

	@Operation(summary = "Thống kê dashboard của gian hàng",
			description = "Chỉ gian hàng ACTIVE. Số liệu đơn hàng lấy từ order-service; top sản phẩm và số "
					+ "sản phẩm đang bán enrich từ product-service.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Thành công"),
			@ApiResponse(responseCode = "404", description = "Chưa đăng ký gian hàng (SHOP_NOT_FOUND)"),
			@ApiResponse(responseCode = "409", description = "Gian hàng chưa hoạt động (SHOP_NOT_ACTIVE)"),
			@ApiResponse(responseCode = "503", description = "Không đọc được order-service (STATS_UNAVAILABLE)"),
	})
	@GetMapping("/me/stats")
	public ShopStatsResponse stats(@AuthenticationPrincipal String userId,
			@RequestParam(defaultValue = "7d") String range) {
		return shopStatsService.stats(userId, range);
	}

	@Operation(summary = "Đơn hàng gần đây của gian hàng",
			description = "Chỉ gian hàng ACTIVE. Tiền và số lượng chỉ tính item thuộc gian hàng này.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Thành công"),
			@ApiResponse(responseCode = "409", description = "Gian hàng chưa hoạt động (SHOP_NOT_ACTIVE)"),
			@ApiResponse(responseCode = "503", description = "Không đọc được order-service "
					+ "(RECENT_ORDERS_UNAVAILABLE)"),
	})
	@GetMapping("/me/recent-orders")
	public List<ShopRecentOrderResponse> recentOrders(@AuthenticationPrincipal String userId,
			@RequestParam(defaultValue = "5")
			@Min(value = 1, message = "limit phải >= 1")
			@Max(value = 20, message = "limit tối đa 20") int limit) {
		return shopStatsService.recentOrders(userId, limit);
	}
}
