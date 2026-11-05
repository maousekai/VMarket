package com.vmarket.user.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import com.vmarket.user.dto.ErrorResponse;
import com.vmarket.user.dto.ProfileResponse;
import com.vmarket.user.dto.UpdateProfileRequest;
import com.vmarket.user.service.UserMediaService;
import com.vmarket.user.service.UserProfileService;

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
 * Hồ sơ của chính người đang đăng nhập (FR-USER-01).
 *
 * <p>Đường dẫn cố định là {@code /me}, <b>không</b> nhận userId từ path hay body:
 * danh tính luôn lấy từ access token đã verify. Nếu để client truyền userId thì
 * bất kỳ ai cũng đổi được hồ sơ người khác chỉ bằng cách sửa một tham số.
 */
@Tag(name = "User Profile", description = "Hồ sơ cá nhân của người dùng đang đăng nhập")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
public class UserProfileController {

	private final UserProfileService userProfileService;
	private final UserMediaService userMediaService;

	@Operation(summary = "Xem hồ sơ của tôi (FR-USER-01)",
			description = "Trả về hồ sơ của người dùng đang đăng nhập. Lần gọi đầu tiên sẽ tự tạo "
					+ "hồ sơ rỗng nên endpoint này không bao giờ trả 404.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Thành công",
					content = @Content(schema = @Schema(implementation = ProfileResponse.class))),
			@ApiResponse(responseCode = "401", description = "Thiếu hoặc sai access token (UNAUTHORIZED)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@GetMapping
	public ProfileResponse getMyProfile(@AuthenticationPrincipal String userId) {
		return userProfileService.getProfile(userId);
	}

	@Operation(summary = "Cập nhật hồ sơ của tôi (FR-USER-01)",
			description = "THAY THẾ toàn bộ hồ sơ: trường không gửi (hoặc gửi null) sẽ bị xoá, "
					+ "không phải giữ nguyên giá trị cũ. Client nên gửi lại cả những trường không đổi.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Cập nhật thành công",
					content = @Content(schema = @Schema(implementation = ProfileResponse.class))),
			@ApiResponse(responseCode = "400",
					description = "Dữ liệu không hợp lệ (VALIDATION_ERROR) hoặc body sai JSON (MALFORMED_REQUEST)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "401", description = "Thiếu hoặc sai access token (UNAUTHORIZED)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@PutMapping
	public ProfileResponse updateMyProfile(@AuthenticationPrincipal String userId,
			@Valid @RequestBody UpdateProfileRequest request) {
		return userProfileService.updateProfile(userId, request);
	}

	@Operation(summary = "Tải ảnh đại diện lên kho ảnh",
			description = "Nhận JPEG/PNG/WebP tối đa 2 MB, tự cắt vuông 512×512 và trả URL công khai. "
					+ "Endpoint KHÔNG ghi vào hồ sơ: gọi PUT /api/users/me với URL này khi người dùng bấm Lưu.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Đã tải lên",
					content = @Content(schema = @Schema(implementation = MediaUploadResponse.class))),
			@ApiResponse(responseCode = "400",
					description = "Thiếu file, quá dung lượng hoặc sai định dạng (MEDIA_*)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "503", description = "Kho ảnh không khả dụng "
					+ "(MEDIA_STORAGE_UNAVAILABLE)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public MediaUploadResponse uploadAvatar(@AuthenticationPrincipal String userId,
			@RequestPart("file") MultipartFile file) {
		return userMediaService.uploadAvatar(userId, file);
	}

	@Operation(summary = "Xoá ảnh đại diện vừa tải lên",
			description = "Chỉ xoá được ảnh thuộc thư mục avatars/{userId} của chính người dùng. "
					+ "Dùng để dọn ảnh khi lưu hồ sơ thất bại; URL ngoài hệ thống bị bỏ qua.")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Đã xoá (hoặc ảnh không còn)"),
			@ApiResponse(responseCode = "400", description = "URL không hợp lệ (INVALID_MEDIA_URL)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "503", description = "Kho ảnh không khả dụng "
					+ "(MEDIA_STORAGE_UNAVAILABLE)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@DeleteMapping("/media")
	public ResponseEntity<Void> deleteMedia(@AuthenticationPrincipal String userId,
			@RequestParam String url) {
		userMediaService.deleteMedia(userId, url);
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}
}
