package com.vmarket.auth.service;

import java.time.Duration;
import java.time.Instant;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Dọn các bản ghi {@code password_reset_token} cũ (đã dùng / hết hạn / bỏ dở) —
 * giữ 1 ngày để phần đếm rate-limit theo giờ vẫn chính xác. Tách riêng khỏi
 * {@link OtpCleanupJob} theo quy ước một job / một mối quan tâm của dự án.
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class PasswordResetCleanupJob {

	private static final Duration RETENTION = Duration.ofDays(1);

	private final PasswordResetService passwordResetService;

	@Scheduled(cron = "0 5 3 * * *")
	public void purge() {
		int removed = passwordResetService.purgeExpired(Instant.now().minus(RETENTION));
		if (removed > 0) {
			log.info("Dọn {} bản ghi password_reset_token cũ", removed);
		}
	}
}
