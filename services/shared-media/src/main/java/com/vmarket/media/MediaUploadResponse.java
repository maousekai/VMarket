package com.vmarket.media;

/** Phản hồi tối giản của endpoint upload: URL công khai để client lưu vào hồ sơ. */
public record MediaUploadResponse(String url) {

	public static MediaUploadResponse of(StoredMedia media) {
		return new MediaUploadResponse(media.url());
	}
}
