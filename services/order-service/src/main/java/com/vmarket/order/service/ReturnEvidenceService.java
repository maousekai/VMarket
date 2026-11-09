package com.vmarket.order.service;

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
import com.vmarket.order.exception.ApiException;
import com.vmarket.order.repository.OrderReturnRepository;

import lombok.RequiredArgsConstructor;

/**
 * Ảnh minh chứng cho yêu cầu trả hàng (FR-ORD-06, PBL6-30).
 *
 * <p>Key nằm dưới {@code returns/{userId}/...} nên người mua chỉ xoá được ảnh của
 * chính mình. Upload chỉ trả URL; URL được gửi kèm {@code evidenceUrls} (tối đa 5)
 * khi tạo yêu cầu. Ảnh đã nằm trong một yêu cầu trả hàng là bằng chứng cho Seller /
 * Admin nên không được xoá nữa.
 */
@Service
@RequiredArgsConstructor
public class ReturnEvidenceService {

	private final ImageProcessor imageProcessor;
	private final MediaStorage mediaStorage;
	private final OrderReturnRepository returnRepository;

	public MediaUploadResponse upload(String userId, MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw ApiException.badRequest("MEDIA_FILE_REQUIRED", "Vui lòng chọn " + ImageKind.EVIDENCE.label());
		}
		byte[] bytes;
		try {
			bytes = file.getBytes();
		} catch (IOException ex) {
			throw ApiException.badRequest("MEDIA_INVALID_IMAGE", "Không đọc được file ảnh");
		}
		try {
			ProcessedImage image = imageProcessor.process(bytes, ImageKind.EVIDENCE);
			StoredMedia stored = mediaStorage.upload(mediaPrefix(userId), image);
			return MediaUploadResponse.of(stored);
		} catch (MediaException ex) {
			throw translate(ex);
		}
	}

	public void delete(String userId, String url) {
		if (url != null && returnRepository.existsByUserIdAndEvidenceUrlsContaining(userId, url)) {
			throw ApiException.conflict("MEDIA_IN_USE", "Ảnh đã được gửi kèm một yêu cầu trả hàng");
		}
		try {
			mediaStorage.deleteByUrl(url, mediaPrefix(userId));
		} catch (MediaException ex) {
			throw translate(ex);
		}
	}

	/** Tiền tố chung của mọi ảnh minh chứng thuộc một người mua. */
	public static String mediaPrefix(String userId) {
		if (userId == null || !userId.matches("[A-Za-z0-9_-]{1,128}")) {
			throw ApiException.badRequest("INVALID_USER", "Không xác định được người dùng");
		}
		return "returns/" + userId;
	}

	private static ApiException translate(MediaException ex) {
		return switch (ex.getReason()) {
			case STORAGE_UNAVAILABLE -> new ApiException("MEDIA_STORAGE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
					"Kho ảnh tạm thời không khả dụng, vui lòng thử lại sau");
			case INVALID_MEDIA_URL -> ApiException.badRequest("INVALID_MEDIA_URL", ex.getMessage());
			default -> ApiException.badRequest("MEDIA_" + ex.getReason().name(), ex.getMessage());
		};
	}
}
