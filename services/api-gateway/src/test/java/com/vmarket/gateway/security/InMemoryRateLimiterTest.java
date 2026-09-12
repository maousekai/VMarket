package com.vmarket.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InMemoryRateLimiterTest {

	@Test
	void respectsCapacityWithinWindow() {
		InMemoryRateLimiter limiter = new InMemoryRateLimiter(3, 60);
		assertThat(limiter.tryAcquire("ip-1")).isTrue();
		assertThat(limiter.tryAcquire("ip-1")).isTrue();
		assertThat(limiter.tryAcquire("ip-1")).isTrue();
		assertThat(limiter.tryAcquire("ip-1")).isFalse();
	}

	@Test
	void keysAreIndependent() {
		InMemoryRateLimiter limiter = new InMemoryRateLimiter(1, 60);
		assertThat(limiter.tryAcquire("ip-a")).isTrue();
		assertThat(limiter.tryAcquire("ip-b")).isTrue();
		assertThat(limiter.tryAcquire("ip-a")).isFalse();
	}

	@Test
	void rejectsInvalidConfiguration() {
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new InMemoryRateLimiter(0, 60));
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new InMemoryRateLimiter(10, 0));
	}

	@Test
	void evictsStaleBucketsWhenWindowAdvances() {
		InMemoryRateLimiter limiter = new InMemoryRateLimiter(5, 60);
		limiter.tryAcquire("ip-1");
		limiter.tryAcquire("ip-2");
		assertThat(limiter.bucketCount()).isEqualTo(2);

		// Khi cửa sổ trôi sang tương lai, các bucket cũ bị dọn dẹp
		long futureWindow = (System.currentTimeMillis() / 1000 / 60) + 10;
		limiter.evictStaleBuckets(futureWindow);
		assertThat(limiter.bucketCount()).isEqualTo(0);
	}
}