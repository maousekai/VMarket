package com.vmarket.media;

/**
 * Lỗi upload / xoá ảnh. Tầng service dịch {@link Reason} sang mã lỗi HTTP của dự án
 * ({@code ApiException}); thư viện dùng chung không phụ thuộc vào package exception
 * của từng service.
 */
public class MediaException extends RuntimeException {

	public enum Reason {
		/** Multipart không có file hoặc file rỗng. */
		FILE_REQUIRED,
		/** File vượt hạn mức của loại ảnh (avatar/logo 2 MB, cover 5 MB). */
		FILE_TOO_LARGE,
		/** Không phải JPEG/PNG/WebP, hoặc là SVG/GIF. */
		UNSUPPORTED_FORMAT,
		/** WebP động (nhiều frame) — không nhận để tránh ảnh động trong hồ sơ. */
		ANIMATED_IMAGE,
		/** Ảnh quá lớn về số điểm ảnh (chống decompression bomb). */
		IMAGE_TOO_LARGE,
		/** Byte không đọc được thành ảnh dù header trông hợp lệ. */
		INVALID_IMAGE,
		/** URL không thuộc bucket/prefix mà người dùng được phép xoá. */
		INVALID_MEDIA_URL,
		/** MinIO không truy cập được hoặc trả lỗi. */
		STORAGE_UNAVAILABLE
	}

	private final Reason reason;

	public MediaException(Reason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public MediaException(Reason reason, String message, Throwable cause) {
		super(message, cause);
		this.reason = reason;
	}

	public Reason getReason() {
		return reason;
	}
}
