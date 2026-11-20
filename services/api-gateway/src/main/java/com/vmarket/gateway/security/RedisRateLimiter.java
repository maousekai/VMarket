package com.vmarket.gateway.security;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Rate limiter fixed-window dùng chung qua Redis: mọi instance gateway tăng cùng
 * một bộ đếm {@code INCR} cho mỗi (cửa sổ, IP), nên hạn mức không nhân lên theo số
 * instance.
 *
 * <p>Key = {@value #KEY_PREFIX} + {@code windowId} + {@code ":"} + IP, TTL = độ dài
 * cửa sổ + 1 giây. {@code INCR} + {@code EXPIRE} chạy trong một Lua script để atomic —
 * không có trường hợp key được tạo mà thiếu TTL. {@code windowId} tính theo đồng hồ
 * của từng instance, nên các instance cần đồng bộ giờ (NTP).
 *
 * <p><b>Khi Redis lỗi:</b> ghi cảnh báo, chuyển sang {@code fallback} (đếm trong RAM
 * của instance này) và bỏ qua Redis trong {@code retryMillis} để request không phải
 * chờ timeout liên tục; hết khoảng đó thì thử Redis lại.
 */
public class RedisRateLimiter implements RateLimiter {

	private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

	static final String KEY_PREFIX = "vmarket:gateway:rate-limit:";

	private static final RedisScript<Long> INCREMENT = new DefaultRedisScript<>(
			"local count = redis.call('INCR', KEYS[1]) "
					+ "if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
					+ "return count",
			Long.class);

	private final StringRedisTemplate redis;
	private final RateLimiter fallback;
	private final int capacity;
	private final long windowSeconds;
	private final long retryMillis;
	private final LongSupplier clockMillis;

	private volatile long redisSkippedUntil;
	private final AtomicBoolean degraded = new AtomicBoolean(false);

	public RedisRateLimiter(StringRedisTemplate redis, RateLimiter fallback, int capacity, int windowSeconds,
			int retrySeconds) {
		this(redis, fallback, capacity, windowSeconds, retrySeconds, System::currentTimeMillis);
	}

	RedisRateLimiter(StringRedisTemplate redis, RateLimiter fallback, int capacity, int windowSeconds,
			int retrySeconds, LongSupplier clockMillis) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("capacity phải > 0");
		}
		if (windowSeconds <= 0) {
			throw new IllegalArgumentException("windowSeconds phải > 0");
		}
		this.redis = redis;
		this.fallback = fallback;
		this.capacity = capacity;
		this.windowSeconds = windowSeconds;
		this.retryMillis = Math.max(0, retrySeconds) * 1000L;
		this.clockMillis = clockMillis;
	}

	@Override
	public boolean tryAcquire(String key) {
		long now = clockMillis.getAsLong();
		if (now < redisSkippedUntil) {
			return fallback.tryAcquire(key);
		}
		long windowId = (now / 1000) / windowSeconds;
		String redisKey = KEY_PREFIX + windowId + ":" + key;
		try {
			Long count = redis.execute(INCREMENT, List.of(redisKey), String.valueOf(windowSeconds + 1));
			if (count == null) {
				throw new IllegalStateException("Redis trả về null cho INCR");
			}
			if (degraded.compareAndSet(true, false)) {
				log.info("Redis hoạt động lại: rate limit quay về bộ đếm dùng chung");
			}
			return count <= capacity;
		} catch (RuntimeException e) {
			redisSkippedUntil = now + retryMillis;
			if (degraded.compareAndSet(false, true)) {
				log.warn("Redis không khả dụng cho rate limit ({}), tạm đếm trong bộ nhớ của instance này",
						e.toString());
			}
			return fallback.tryAcquire(key);
		}
	}
}
