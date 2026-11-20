package com.vmarket.gateway.security;

/** Bộ giới hạn tốc độ theo khoá (client IP). */
public interface RateLimiter {

	/**
	 * Thử tiêu thụ 1 request cho {@code key}.
	 *
	 * @return {@code true} nếu còn hạn mức, {@code false} nếu vượt ngưỡng.
	 */
	boolean tryAcquire(String key);
}
