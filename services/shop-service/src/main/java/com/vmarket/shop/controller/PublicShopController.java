package com.vmarket.shop.controller;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.shop.dto.ErrorResponse;
import com.vmarket.shop.dto.PublicShopResponse;
import com.vmarket.shop.service.ShopService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;

/** FR-SHOP-03 — Trang gian hàng công khai. Không cần đăng nhập. */
@Tag(name = "Shops - Public", description = "Trang gian hàng công khai (FR-SHOP-03)")
@RestController
@RequestMapping("/api/shops")
@Validated
@RequiredArgsConstructor
public class PublicShopController {

	private final ShopService shopService;

	@Operation(summary = "Xem trang gian hàng",
			description = "Chỉ trả gian hàng đang hoạt động; gian hàng chờ duyệt / bị từ chối / bị đình chỉ "
					+ "trả 404 như không tồn tại. Danh sách sản phẩm lấy ở Product Catalog "
					+ "(`GET /api/products?shopId=...`).")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Thành công",
					content = @Content(schema = @Schema(implementation = PublicShopResponse.class))),
			@ApiResponse(responseCode = "400", description = "shopId sai định dạng (VALIDATION_ERROR)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "Không tìm thấy gian hàng (SHOP_NOT_FOUND)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@GetMapping("/{shopId}")
	public PublicShopResponse get(
			@PathVariable @Pattern(regexp = ShopIds.PATTERN, message = ShopIds.MESSAGE) String shopId) {
		return shopService.getPublic(shopId);
	}
}
