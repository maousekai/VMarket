package com.vmarket.gateway.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.vmarket.gateway.security.ClientIpResolver;
import com.vmarket.gateway.security.InMemoryRateLimiter;
import com.vmarket.gateway.security.RateLimiter;
import com.vmarket.gateway.security.RedisRateLimiter;

import io.lettuce.core.ClientOptions;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/** Tạo {@link RateLimiter} và {@link ClientIpResolver} theo {@link RateLimitProperties}. */
@Configuration(proxyBeanMethods = false)
public class RateLimitConfig {

	@Bean
	ClientIpResolver clientIpResolver(RateLimitProperties properties) {
		return new ClientIpResolver(properties.getTrustedProxies(), properties.getTrustedProxyRefreshSeconds());
	}

	/**
	 * Mất kết nối Redis thì từ chối lệnh ngay thay vì xếp hàng chờ reconnect, để cả
	 * đợt request đang chạy không phải chờ đủ timeout trước khi sang bộ đếm dự phòng.
	 * Redis của gateway chỉ dùng cho rate limit nên không có lệnh nào cần giữ lại.
	 */
	@Bean
	LettuceClientOptionsBuilderCustomizer rateLimitRedisFailFast() {
		return builder -> builder.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
	}

	@Bean
	RateLimiter rateLimiter(RateLimitProperties properties, ObjectProvider<StringRedisTemplate> redis,
			ObjectProvider<MeterRegistry> meterRegistry) {
		InMemoryRateLimiter memory = new InMemoryRateLimiter(properties.getCapacity(), properties.getWindowSeconds());
		if (properties.getStore() != RateLimitProperties.Store.REDIS) {
			return memory;
		}
		StringRedisTemplate template = redis.getIfAvailable();
		if (template == null) {
			throw new IllegalStateException("app.rate-limit.store=redis nhưng không có StringRedisTemplate");
		}
		RedisRateLimiter limiter = new RedisRateLimiter(template, memory, properties.getCapacity(),
				properties.getWindowSeconds(), properties.getRedisRetrySeconds());
		// 1 = đang đếm trong RAM do Redis lỗi (hạn mức nhân theo số instance); cần cảnh báo được.
		meterRegistry.ifAvailable(registry -> Gauge
				.builder("gateway.rate_limit.degraded", limiter, l -> l.isDegraded() ? 1 : 0)
				.description("1 khi rate limit đang dùng bộ đếm dự phòng trong RAM vì Redis lỗi")
				.register(registry));
		return limiter;
	}
}
