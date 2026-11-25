package com.vmarket.gateway.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình rate limit (fixed-window theo client IP) tại gateway.
 *
 * <p><b>Khoá đếm</b> là IP client thật, do {@code ClientIpResolver} xác định:
 * mặc định là {@code getRemoteAddr()}. {@code X-Forwarded-For} chỉ được đọc khi
 * kết nối đến từ một proxy nằm trong {@link #trustedProxies} (vd nginx của
 * frontend) — client gọi thẳng gateway không thể tự đặt XFF để né giới hạn.
 *
 * <p><b>Nơi lưu bộ đếm</b> ({@link #store}):
 * <ul>
 * <li>{@code memory} — bộ đếm trong RAM của từng instance gateway (dev, chạy một
 * instance).</li>
 * <li>{@code redis} — bộ đếm dùng chung qua Redis để nhiều instance gateway chia
 * nhau một hạn mức. Khi Redis lỗi, gateway tạm đếm trong RAM (vẫn còn giới hạn,
 * chỉ là theo từng instance) cho đến khi Redis hoạt động lại.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

	public enum Store {
		MEMORY, REDIS
	}

	/** Bật/tắt rate limit. */
	private boolean enabled = true;

	/** Số request tối đa trong một cửa sổ (theo mỗi IP). */
	private int capacity = 200;

	/** Độ dài cửa sổ tính bằng giây. */
	private int windowSeconds = 60;

	/** Nơi lưu bộ đếm: {@code memory} hoặc {@code redis}. */
	private Store store = Store.MEMORY;

	/**
	 * Các proxy được tin để đọc {@code X-Forwarded-For}: địa chỉ IP, dải CIDR
	 * (vd {@code 10.0.0.0/8}) hoặc tên host (vd {@code frontend} trong
	 * docker-compose; được tra DNS định kỳ vì IP container thay đổi khi khởi động
	 * lại). Để trống = không tin XFF từ bất kỳ đâu.
	 */
	private List<String> trustedProxies = new ArrayList<>();

	/** Chu kỳ tra lại DNS cho các tên host trong {@link #trustedProxies} (giây). */
	private int trustedProxyRefreshSeconds = 30;

	/** Sau khi Redis lỗi, bỏ qua Redis trong khoảng này (giây) rồi mới thử lại. */
	private int redisRetrySeconds = 5;

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

	public Store getStore() {
		return store;
	}

	public void setStore(Store store) {
		this.store = store;
	}

	public List<String> getTrustedProxies() {
		return trustedProxies;
	}

	public void setTrustedProxies(List<String> trustedProxies) {
		this.trustedProxies = trustedProxies == null ? new ArrayList<>() : trustedProxies;
	}

	public int getTrustedProxyRefreshSeconds() {
		return trustedProxyRefreshSeconds;
	}

	public void setTrustedProxyRefreshSeconds(int trustedProxyRefreshSeconds) {
		this.trustedProxyRefreshSeconds = trustedProxyRefreshSeconds;
	}

	public int getRedisRetrySeconds() {
		return redisRetrySeconds;
	}

	public void setRedisRetrySeconds(int redisRetrySeconds) {
		this.redisRetrySeconds = redisRetrySeconds;
	}
}
