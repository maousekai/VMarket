package com.vmarket.user.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.user.dto.AddressRequest;
import com.vmarket.user.dto.AddressResponse;
import com.vmarket.user.dto.ErrorResponse;
import com.vmarket.user.service.AddressService;

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
 * Sổ địa chỉ giao hàng của người đang đăng nhập (FR-USER-02).
 *
 * <p>Mọi endpoint đều thao tác trong phạm vi {@code /me} nên không thể chạm vào
 * địa chỉ của người khác, kể cả khi biết id.
 */
@Tag(name = "Address Book", description = "Sổ địa chỉ giao hàng (FR-USER-02)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/users/me/addresses")
@RequiredArgsConstructor
public class AddressController {

	private final AddressService addressService;

	@Operation(summary = "Danh sách địa chỉ của tôi",
			description = "Địa chỉ mặc định luôn đứng đầu, sau đó tới địa chỉ mới thêm gần nhất.")
	@ApiResponse(responseCode = "200", description = "Thành công")
	@GetMapping
	public List<AddressResponse> list(@AuthenticationPrincipal String userId) {
		return addressService.list(userId);
	}

	@Operation(summary = "Xem chi tiết một địa chỉ")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Thành công",
					content = @Content(schema = @Schema(implementation = AddressResponse.class))),
			@ApiResponse(responseCode = "404", description = "Không tìm thấy địa chỉ (ADDRESS_NOT_FOUND)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@GetMapping("/{addressId}")
	public AddressResponse get(@AuthenticationPrincipal String userId, @PathVariable String addressId) {
		return addressService.get(userId, addressId);
	}

	@Operation(summary = "Thêm địa chỉ mới",
			description = "Địa chỉ ĐẦU TIÊN của người dùng tự động trở thành địa chỉ mặc định.")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Đã thêm",
					content = @Content(schema = @Schema(implementation = AddressResponse.class))),
			@ApiResponse(responseCode = "400", description = "Dữ liệu không hợp lệ (VALIDATION_ERROR)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@PostMapping
	public ResponseEntity<AddressResponse> create(@AuthenticationPrincipal String userId,
			@Valid @RequestBody AddressRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(addressService.create(userId, request));
	}

	@Operation(summary = "Sửa một địa chỉ",
			description = "KHÔNG đổi cờ mặc định — dùng endpoint /default cho việc đó.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Đã cập nhật",
					content = @Content(schema = @Schema(implementation = AddressResponse.class))),
			@ApiResponse(responseCode = "400", description = "Dữ liệu không hợp lệ (VALIDATION_ERROR)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "Không tìm thấy địa chỉ (ADDRESS_NOT_FOUND)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@PutMapping("/{addressId}")
	public AddressResponse update(@AuthenticationPrincipal String userId,
			@PathVariable String addressId,
			@Valid @RequestBody AddressRequest request) {
		return addressService.update(userId, addressId, request);
	}

	@Operation(summary = "Xoá một địa chỉ",
			description = "Nếu xoá đúng địa chỉ đang là mặc định và vẫn còn địa chỉ khác, "
					+ "địa chỉ mới nhất còn lại sẽ tự trở thành mặc định.")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Đã xoá"),
			@ApiResponse(responseCode = "404", description = "Không tìm thấy địa chỉ (ADDRESS_NOT_FOUND)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@DeleteMapping("/{addressId}")
	public ResponseEntity<Void> delete(@AuthenticationPrincipal String userId, @PathVariable String addressId) {
		addressService.delete(userId, addressId);
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "Đặt địa chỉ mặc định (FR-USER-02)",
			description = "Đặt địa chỉ này làm mặc định và tự gỡ cờ mặc định của địa chỉ trước đó. "
					+ "Gọi lại trên địa chỉ đã là mặc định thì không đổi gì (idempotent).")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Đã đặt mặc định",
					content = @Content(schema = @Schema(implementation = AddressResponse.class))),
			@ApiResponse(responseCode = "404", description = "Không tìm thấy địa chỉ (ADDRESS_NOT_FOUND)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "409",
					description = "Hai thao tác đặt mặc định chạy song song (DEFAULT_ADDRESS_CONFLICT)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@PutMapping("/{addressId}/default")
	public AddressResponse setDefault(@AuthenticationPrincipal String userId, @PathVariable String addressId) {
		return addressService.setDefault(userId, addressId);
	}
}
