package com.vmarket.media;

import java.util.Arrays;
import java.util.Objects;

/**
 * Ảnh đã được kiểm tra, cắt/co và mã hoá lại. Byte trong record này KHÔNG còn metadata
 * EXIF/GPS của file gốc.
 */
public record ProcessedImage(byte[] bytes, String contentType, String extension) {

	public ProcessedImage {
		Objects.requireNonNull(bytes, "bytes");
		Objects.requireNonNull(contentType, "contentType");
		Objects.requireNonNull(extension, "extension");
		bytes = Arrays.copyOf(bytes, bytes.length);
	}

	@Override
	public byte[] bytes() {
		return Arrays.copyOf(bytes, bytes.length);
	}

	public int size() {
		return bytes.length;
	}
}
