package com.vmarket.auth.security;

/**
 * Thông tin thiết bị best-effort gắn vào một refresh token lúc phát/xoay vòng,
 * chỉ để hiển thị ở danh sách phiên (FR-AUTH-06) — KHÔNG dùng cho quyết định bảo mật.
 */
public record DeviceMeta(String userAgent, String ipAddress) {
}
