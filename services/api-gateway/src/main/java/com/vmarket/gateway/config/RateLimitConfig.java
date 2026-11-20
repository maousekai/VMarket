package com.vmarket.gateway.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.vmarket.gateway.security.ClientIpResolver;
import com.vmarket.gateway.security.InMemoryRateLimiter;
import com.vmarket.gateway.security.RateLimiter;
import com.vmarket.gateway.security.RedisRateLimiter;

/** Tạo {@link RateLimiter} và {@link ClientIpResolver} theo {@link RateLimitProperties}. */
@Configuration(proxyBeanMethods = false)
public class RateLimitConfig {

	@Bean
	ClientIpResolver clientIpResolver(RateLimitProperties properties) {
		return new ClientIpResolver(properties.getTrustedProxies(), properties.getTrustedProxyRefreshSeconds());
	}

	@Bean
	RateLimiter rateLimiter(RateLimitProperties properties, ObjectProvider<StringRedisTemplate> redis) {
		InMemoryRateLimiter memory = new InMemoryRateLimiter(properties.getCapacity(), properties.getWindowSeconds());
		if (properties.getStore() != RateLimitProperties.Store.REDIS) {
			return memory;
		}
		StringRedisTemplate template = redis.getIfAvailable();
		if (template == null) {
			throw new IllegalStateException("app.rate-limit.store=redis nhưng không có StringRedisTemplate");
		}
		return new RedisRateLimiter(template, memory, properties.getCapacity(), properties.getWindowSeconds(),
				properties.getRedisRetrySeconds());
	}
}
