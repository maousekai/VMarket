package com.vmarket.review.service;

import java.io.IOException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.vmarket.media.ImageKind;
import com.vmarket.media.ImageProcessor;
import com.vmarket.media.MediaException;
import com.vmarket.media.MediaStorage;
import com.vmarket.media.MediaUploadResponse;
import com.vmarket.media.ProcessedImage;
import com.vmarket.media.StoredMedia;
import com.vmarket.review.exception.ApiException;
import com.vmarket.review.repository.ReviewRepository;

import lombok.RequiredArgsConstructor;

/**
 * Ảnh thực tế người mua đính kèm đánh giá (FR-REV-01, tối đa 5 ảnh / đánh giá).
 *
 * <p>Upload chỉ trả URL; URL được gửi kèm {@code imageUrls} khi tạo đánh giá. Ảnh đánh giá công khai
 * nên key không được chứa buyerId (API công khai đã giấu người mua): mỗi lần tải dùng một thư mục
 * ngẫu nhiên {@code reviews/{random}/...}, còn chủ sở hữu được lưu ở bảng {@code review_media}. Người
 * mua chỉ xoá được ảnh của chính mình, và ảnh đã nằm trong một đánh giá thì không xoá được nữa.
 */
@Service
@RequiredArgsConstructor
public class ReviewMediaService {

	/** Thư mục gốc của ảnh đánh giá trong kho ảnh (bucket policy cho đọc công khai {@code reviews/*}). */
	static final String MEDIA_ROOT = "reviews";
	private static final SecureRandom RANDOM = new SecureRandom();

	private final ImageProcessor imageProcessor;
	private final MediaStorage mediaStorage;
	private final ReviewRepository reviews;
	private final JdbcTemplate jdbc;
	private final Clock clock;

	public MediaUploadResponse upload(String buyerId, MultipartFile file) {
		String owner = requireBuyer(buyerId);
		if (file == null || file.isEmpty()) {
			throw ApiException.badRequest("MEDIA_FILE_REQUIRED", "Vui lòng chọn " + ImageKind.REVIEW.label());
		}
		byte[] bytes;
		try {
			bytes = file.getBytes();
		} catch (IOException ex) {
			throw ApiException.badRequest("MEDIA_INVALID_IMAGE", "Không đọc được file ảnh");
		}
		try {
			ProcessedImage image = imageProcessor.process(bytes, ImageKind.REVIEW);
			StoredMedia stored = mediaStorage.upload(MEDIA_ROOT + "/" + randomFolder(), image);
			jdbc.update("insert into review_media (url, buyer_id, created_at) values (?, ?, ?)", stored.url(), owner,
					Timestamp.from(clock.instant()));
			return MediaUploadResponse.of(stored);
		} catch (MediaException ex) {
			throw translate(ex);
		}
	}

	public void delete(String buyerId, String url) {
		List<String> owners = url == null ? List.of()
				: jdbc.queryForList("select buyer_id from review_media where url = ?", String.class, url);
		if (owners.isEmpty() || !owners.get(0).equals(requireBuyer(buyerId))) {
			throw ApiException.badRequest("INVALID_MEDIA_URL", "URL ảnh không thuộc kho ảnh của bạn");
		}
		if (reviews.isImageUsed(buyerId, url)) {
			throw ApiException.conflict("MEDIA_IN_USE", "Ảnh đã được gửi kèm một đánh giá");
		}
		try {
			mediaStorage.deleteByUrl(url, MEDIA_ROOT);
		} catch (MediaException ex) {
			throw translate(ex);
		}
		jdbc.update("delete from review_media where url = ?", url);
	}

	private static String requireBuyer(String buyerId) {
		if (buyerId == null || !buyerId.matches("[A-Za-z0-9_-]{1,64}")) {
			throw ApiException.badRequest("INVALID_USER", "Không xác định được người dùng");
		}
		return buyerId;
	}

	/** 128 bit ngẫu nhiên: không đoán được và không liên kết được các ảnh của cùng một người mua. */
	private static String randomFolder() {
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
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
