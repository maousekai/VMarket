package com.vmarket.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình rate limit cơ bản (fixed-window theo IP) tại gateway.
 *
 * <p>Đây là giải pháp <b>in-memory, chạy độc lập theo từng instance gateway</b>
 * (mỗi instance giữ bộ đếm riêng) — đủ cho môi trường dev / một instance. Khi
 * chạy nhiều instance cần giới hạn phân tán thì thay bằng Redis (INCR+EXPIRE),
 * nhưng không nằm trong phạm vi PBL6-38 ("rate limiting cơ bản").
 *
 * <p>Khoá đếm là địa chỉ client IP (ưu tiên {@code X-Forwarded-For}, fallback
 * {@code getRemoteAddr()}).
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

	/** Bật/tắt rate limit. */
	private boolean enabled = true;

	/** Số request tối đa trong một cửa sổ (theo mỗi IP). */
	private int capacity = 200;

	/** Độ dài cửa sổ tính bằng giây. */
	private int windowSeconds = 60;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public int getCapacity() {
		return capacity;
	}

	public void setCapacity(int capacity) {
		this.capacity = capacity;
	}

	public int getWindowSeconds() {
		return windowSeconds;
	}

	public void setWindowSeconds(int windowSeconds) {
		this.windowSeconds = windowSeconds;
	}
}