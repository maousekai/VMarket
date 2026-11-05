package com.vmarket.user.service;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.vmarket.media.ImageKind;
import com.vmarket.media.ImageProcessor;
import com.vmarket.media.MediaException;
import com.vmarket.media.MediaStorage;
import com.vmarket.media.MediaUploadResponse;
import com.vmarket.media.ProcessedImage;
import com.vmarket.media.StoredMedia;
import com.vmarket.user.exception.ApiException;

import lombok.RequiredArgsConstructor;

/**
 * Ảnh đại diện của người dùng trên kho ảnh dùng chung.
 *
 * <p>Endpoint upload chỉ trả URL, <b>không</b> ghi vào hồ sơ: người dùng còn có thể
 * chọn ảnh khác hoặc bấm Huỷ. Frontend gọi {@code PUT /api/users/me} với URL nhận được
 * khi bấm Lưu; nếu lưu hồ sơ thất bại, frontend gọi endpoint xoá để dọn ảnh vừa tải.
 */
@Service
@RequiredArgsConstructor
public class UserMediaService {

	private final ImageProcessor imageProcessor;
	private final MediaStorage mediaStorage;

	public MediaUploadResponse uploadAvatar(String userId, MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw ApiException.badRequest("MEDIA_FILE_REQUIRED", "Vui lòng chọn ảnh đại diện");
		}
		byte[] bytes;
		try {
			bytes = file.getBytes();
		} catch (IOException ex) {
			throw ApiException.badRequest("MEDIA_INVALID_IMAGE", "Không đọc được file ảnh");
		}

		try {
			ProcessedImage image = imageProcessor.process(bytes, ImageKind.AVATAR);
			StoredMedia stored = mediaStorage.upload(avatarPrefix(userId), image);
			return MediaUploadResponse.of(stored);
		} catch (MediaException ex) {
			throw translate(ex);
		}
	}

	public void deleteMedia(String userId, String url) {
		try {
			mediaStorage.deleteByUrl(url, avatarPrefix(userId));
		} catch (MediaException ex) {
			throw translate(ex);
		}
	}

	/** Tiền tố key của chính người dùng; chặn userId lạ chứa dấu / hoặc .. . */
	public static String avatarPrefix(String userId) {
		if (userId == null || !userId.matches("[A-Za-z0-9_-]{1,128}")) {
			throw ApiException.badRequest("INVALID_USER", "Không xác định được người dùng");
		}
		return "avatars/" + userId;
	}

	private static ApiException translate(MediaException ex) {
		return switch (ex.getReason()) {
			case STORAGE_UNAVAILABLE -> new ApiException("MEDIA_STORAGE_UNAVAILABLE",
					HttpStatus.SERVICE_UNAVAILABLE,
					"Kho ảnh tạm thời không khả dụng, vui lòng thử lại sau");
			case INVALID_MEDIA_URL -> ApiException.badRequest("INVALID_MEDIA_URL", ex.getMessage());
			default -> ApiException.badRequest("MEDIA_" + ex.getReason().name(), ex.getMessage());
		};
	}
}
