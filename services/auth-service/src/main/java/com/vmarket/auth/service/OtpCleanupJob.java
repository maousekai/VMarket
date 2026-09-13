package com.vmarket.auth.service;

import java.time.Duration;
import java.time.Instant;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Dọn các bản ghi {@code email_otp} cũ (đã dùng / hết hạn / bỏ dở) — giữ 1 ngày
 * để phần đếm rate-limit theo giờ vẫn chính xác.
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class OtpCleanupJob {

	private static final Duration RETENTION = Duration.ofDays(1);

	private final OtpService otpService;

	@Scheduled(cron = "0 0 3 * * *")
	public void purge() {
		int removed = otpService.purgeExpired(Instant.now().minus(RETENTION));
		if (removed > 0) {
			log.info("Dọn {} bản ghi email_otp cũ", removed);
		}
	}
}
