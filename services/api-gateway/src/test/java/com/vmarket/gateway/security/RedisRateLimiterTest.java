package com.vmarket.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class RedisRateLimiterTest {

	/** Redis giả: đếm INCR theo key, có thể bật chế độ lỗi. */
	private static final class FakeRedis extends StringRedisTemplate {
		final Map<String, Long> counters = new HashMap<>();
		final List<String> ttls = new ArrayList<>();
		boolean down;
		int calls;

		@Override
		@SuppressWarnings("unchecked")
		public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
			calls++;
			if (down) {
				throw new RedisConnectionFailureException("redis down");
			}
			ttls.add((String) args[0]);
			return (T) counters.merge(keys.get(0), 1L, Long::sum);
		}
	}

	@Test
	void countsAreSharedThroughRedis() {
		FakeRedis redis = new FakeRedis();
		AtomicLong clock = new AtomicLong(120_000);
		// Hai "instance" gateway dùng chung một Redis
		RedisRateLimiter a = new RedisRateLimiter(redis, new InMemoryRateLimiter(2, 60), 2, 60, 5, clock::get);
		RedisRateLimiter b = new RedisRateLimiter(redis, new InMemoryRateLimiter(2, 60), 2, 60, 5, clock::get);

		assertThat(a.tryAcquire("198.51.100.1")).isTrue();
		assertThat(b.tryAcquire("198.51.100.1")).isTrue();
		assertThat(a.tryAcquire("198.51.100.1")).isFalse();
		assertThat(b.tryAcquire("198.51.100.2")).isTrue();
		assertThat(redis.counters).containsKey(RedisRateLimiter.KEY_PREFIX + "2:198.51.100.1");
		assertThat(redis.ttls).containsOnly("61");
	}

	@Test
	void newWindowStartsFreshCounter() {
		FakeRedis redis = new FakeRedis();
		AtomicLong clock = new AtomicLong(0);
		RedisRateLimiter limiter = new RedisRateLimiter(redis, new InMemoryRateLimiter(1, 60), 1, 60, 5, clock::get);

		assertThat(limiter.tryAcquire("ip")).isTrue();
		assertThat(limiter.tryAcquire("ip")).isFalse();
		clock.set(60_000);
		assertThat(limiter.tryAcquire("ip")).isTrue();
	}

	@Test
	void redisDown_fallsBackToMemory_andRetriesAfterBackoff() {
		FakeRedis redis = new FakeRedis();
		redis.down = true;
		AtomicLong clock = new AtomicLong(0);
		RedisRateLimiter limiter = new RedisRateLimiter(redis, new InMemoryRateLimiter(1, 60), 1, 60, 5, clock::get);

		// Vẫn còn giới hạn nhờ bộ đếm trong RAM
		assertThat(limiter.tryAcquire("ip")).isTrue();
		assertThat(limiter.tryAcquire("ip")).isFalse();
		// Trong thời gian backoff không gọi Redis nữa
		assertThat(redis.calls).isEqualTo(1);

		redis.down = false;
		clock.set(5_000);
		assertThat(limiter.tryAcquire("other")).isTrue();
		assertThat(redis.calls).isEqualTo(2);
	}

	@Test
	void rejectsInvalidConfiguration() {
		FakeRedis redis = new FakeRedis();
		InMemoryRateLimiter memory = new InMemoryRateLimiter(1, 60);
		assertThrows(IllegalArgumentException.class, () -> new RedisRateLimiter(redis, memory, 0, 60, 5));
		assertThrows(IllegalArgumentException.class, () -> new RedisRateLimiter(redis, memory, 1, 0, 5));
	}
}
