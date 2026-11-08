package com.vmarket.media;

/** Kết quả upload: key trong bucket và URL công khai browser có thể dùng ngay. */
public record StoredMedia(String key, String url) {
}
