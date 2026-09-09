package com.vmarket.gateway.security;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bộ giới hạn tốc độ fixed-window theo khoá (thường là client IP), lưu trong bộ
 * nhớ của gateway instance. Đơn giản, không phụ thuộc hạ tầng ngoài — phù hợp
 * yêu cầu "rate limiting cơ bản" của PBL6-38.
 *
 * <p>Mỗi khoá giữ một "bucket" gồm {@code window} (định danh cửa sổ hiện tại) và
 * bộ đếm. Khi cửa sổ trôi sang mới thì reset đếm về 0. Thao tác trên một bucket
 * được {@code synchronized} để tránh race trong cùng một khoá.
 */
public class InMemoryRateLimiter {

	private static final class Bucket {
		private long window;
		private final AtomicInteger count = new AtomicInteger(0);

		private Bucket(long window) {
			this.window = window;
		}
	}

	private final int capacity;
	private final long windowSeconds;
	private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

	public InMemoryRateLimiter(int capacity, int windowSeconds) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("capacity phải > 0");
		}
		if (windowSeconds <= 0) {
			throw new IllegalArgumentException("windowSeconds phải > 0");
		}
		this.capacity = capacity;
		this.windowSeconds = windowSeconds;
	}

	/**
	 * Thử tiêu thụ 1 request cho {@code key}.
	 *
	 * @return {@code true} nếu còn hạn mức, {@code false} nếu vượt ngưỡng.
	 */
	public boolean tryAcquire(String key) {
		long now = System.currentTimeMillis() / 1000;
		long windowId = now / windowSeconds;

		Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(windowId));
		synchronized (bucket) {
			if (bucket.window != windowId) {
				bucket.window = windowId;
				bucket.count.set(0);
			}
			return bucket.count.incrementAndGet() <= capacity;
		}
	}
}