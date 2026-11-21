package com.vmarket.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Chạy Lua script của {@link RedisRateLimiter} trên Redis THẬT. {@code RedisRateLimiterTest}
 * dùng Redis giả nên không bắt được lỗi cú pháp Lua, sai kiểu trả về hay lỗi serializer —
 * những lỗi đó khi chạy thật chỉ làm limiter âm thầm quay về đếm trong RAM.
 * Máy không có Docker tự bỏ qua.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisRateLimiterRedisTest {

	@Container
	static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379);

	private LettuceConnectionFactory factory;
	private StringRedisTemplate redis;

	@BeforeEach
	void connect() {
		factory = new LettuceConnectionFactory(
				new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
		factory.afterPropertiesSet();
		factory.start();
		redis = new StringRedisTemplate(factory);
		redis.getConnectionFactory().getConnection().serverCommands().flushAll();
	}

	@AfterEach
	void close() {
		factory.destroy();
	}

	@Test
	void scriptCountsAndSetsTtl_onRealRedis() {
		AtomicLong clock = new AtomicLong(120_000);
		RedisRateLimiter limiter = new RedisRateLimiter(redis, new InMemoryRateLimiter(2, 60), 2, 60, 5, clock::get);

		assertThat(limiter.tryAcquire("198.51.100.1")).isTrue();
		assertThat(limiter.tryAcquire("198.51.100.1")).isTrue();
		assertThat(limiter.tryAcquire("198.51.100.1")).isFalse();
		// Không bao giờ phải dùng bộ dự phòng khi Redis khoẻ
		assertThat(limiter.isDegraded()).isFalse();

		String key = RedisRateLimiter.KEY_PREFIX + "2:198.51.100.1";
		assertThat(redis.opsForValue().get(key)).isEqualTo("3");
		Long ttl = redis.getExpire(key);
		assertThat(ttl).isNotNull().isBetween(1L, 61L);
	}
}
