package com.vmarket.product.service;

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
import com.vmarket.product.exception.ApiException;
import com.vmarket.product.repository.ProductRepository;

/**
 * Ảnh sản phẩm trên kho ảnh dùng chung (PBL6-28).
 *
 * <p>Key nằm dưới {@code products/{sellerId}/...} nên seller chỉ xoá được ảnh của
 * chính mình. Upload chỉ trả URL; việc gắn ảnh vào sản phẩm vẫn do
 * {@code POST/PUT /api/products} (trường {@code imageUrls}, tối đa 9 ảnh) quyết định.
 * Form tải ảnh lên trước khi lưu nên ảnh bị bỏ dở được dọn qua endpoint xoá.
 */
@Service
public class ProductMediaService {

	private final ImageProcessor imageProcessor;
	private final MediaStorage mediaStorage;
	private final ProductRepository productRepository;

	public ProductMediaService(ImageProcessor imageProcessor, MediaStorage mediaStorage,
			ProductRepository productRepository) {
		this.imageProcessor = imageProcessor;
		this.mediaStorage = mediaStorage;
		this.productRepository = productRepository;
	}

	public MediaUploadResponse upload(String sellerId, MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "MEDIA_FILE_REQUIRED",
					"Vui lòng chọn " + ImageKind.PRODUCT.label());
		}
		byte[] bytes;
		try {
			bytes = file.getBytes();
		} catch (IOException ex) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "MEDIA_INVALID_IMAGE", "Không đọc được file ảnh");
		}
		try {
			ProcessedImage image = imageProcessor.process(bytes, ImageKind.PRODUCT);
			StoredMedia stored = mediaStorage.upload(mediaPrefix(sellerId), image);
			return MediaUploadResponse.of(stored);
		} catch (MediaException ex) {
			throw translate(ex);
		}
	}

	public void delete(String sellerId, String url) {
		if (url != null && productRepository.existsBySellerIdAndImageUrlsAndDeletedAtIsNull(sellerId, url)) {
			throw new ApiException(HttpStatus.CONFLICT, "MEDIA_IN_USE", "Ảnh đang được dùng cho một sản phẩm");
		}
		try {
			mediaStorage.deleteByUrl(url, mediaPrefix(sellerId));
		} catch (MediaException ex) {
			throw translate(ex);
		}
	}

	/** Tiền tố chung của mọi ảnh sản phẩm thuộc một seller. */
	public static String mediaPrefix(String sellerId) {
		if (sellerId == null || !sellerId.matches("[A-Za-z0-9_-]{1,128}")) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_USER", "Không xác định được người dùng");
		}
		return "products/" + sellerId;
	}

	private static ApiException translate(MediaException ex) {
		return switch (ex.getReason()) {
			case STORAGE_UNAVAILABLE -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
					"MEDIA_STORAGE_UNAVAILABLE", "Kho ảnh tạm thời không khả dụng, vui lòng thử lại sau");
			case INVALID_MEDIA_URL -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MEDIA_URL", ex.getMessage());
			default -> new ApiException(HttpStatus.BAD_REQUEST, "MEDIA_" + ex.getReason().name(), ex.getMessage());
		};
	}
}
