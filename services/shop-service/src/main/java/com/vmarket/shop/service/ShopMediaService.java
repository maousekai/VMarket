package com.vmarket.shop.service;

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
import com.vmarket.shop.exception.ApiException;

import lombok.RequiredArgsConstructor;

/**
 * Logo và ảnh bìa gian hàng trên kho ảnh dùng chung.
 *
 * <p>Key nằm dưới {@code shops/{ownerId}/...} nên người bán chỉ xoá được ảnh của
 * chính mình. Upload không cần gian hàng đã tồn tại: form đăng ký phải tải ảnh lên
 * trước khi gọi {@code POST /api/shops}. Endpoint chỉ trả URL, việc ghi vào hồ sơ vẫn
 * do {@code POST /api/shops} hoặc {@code PUT /api/shops/me} thực hiện.
 */
@Service
@RequiredArgsConstructor
public class ShopMediaService {

	private final ImageProcessor imageProcessor;
	private final MediaStorage mediaStorage;

	public MediaUploadResponse uploadLogo(String ownerId, MultipartFile file) {
		return upload(ownerId, file, ImageKind.LOGO, "logo");
	}

	public MediaUploadResponse uploadCover(String ownerId, MultipartFile file) {
		return upload(ownerId, file, ImageKind.COVER, "cover");
	}

	public void deleteMedia(String ownerId, String url) {
		try {
			mediaStorage.deleteByUrl(url, mediaPrefix(ownerId));
		} catch (MediaException ex) {
			throw translate(ex);
		}
	}

	private MediaUploadResponse upload(String ownerId, MultipartFile file, ImageKind kind,
			String area) {
		if (file == null || file.isEmpty()) {
			throw ApiException.badRequest("MEDIA_FILE_REQUIRED",
					"Vui lòng chọn " + kind.label());
		}
		byte[] bytes;
		try {
			bytes = file.getBytes();
		} catch (IOException ex) {
			throw ApiException.badRequest("MEDIA_INVALID_IMAGE", "Không đọc được file ảnh");
		}

		try {
			ProcessedImage image = imageProcessor.process(bytes, kind);
			StoredMedia stored = mediaStorage.upload(mediaPrefix(ownerId) + "/" + area, image);
			return MediaUploadResponse.of(stored);
		} catch (MediaException ex) {
			throw translate(ex);
		}
	}

	/** Tiền tố chung của mọi ảnh thuộc gian hàng; dùng cho endpoint xoá. */
	public static String mediaPrefix(String ownerId) {
		if (ownerId == null || !ownerId.matches("[A-Za-z0-9_-]{1,128}")) {
			throw ApiException.badRequest("INVALID_USER", "Không xác định được người dùng");
		}
		return "shops/" + ownerId;
	}

	public static String logoPrefix(String ownerId) {
		return mediaPrefix(ownerId) + "/logo";
	}

	public static String coverPrefix(String ownerId) {
		return mediaPrefix(ownerId) + "/cover";
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
