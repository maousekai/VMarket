package com.vmarket.auth.email;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Implementation dev/test: KHÔNG gửi email thật, chỉ ghi toàn bộ nội dung (kể cả
 * mã OTP) ra log. Dùng khi chưa cấu hình provider thật — chạy local / CI không
 * cần API key.
 *
 * <p>Kích hoạt khi {@code auth.email.provider=log} hoặc không đặt (mặc định).
 * Ở profile {@code prod}, cấu hình này bị coi là lỗi khởi động (fail-fast) thay
 * vì chỉ cảnh báo — tránh trường hợp quên set {@code AUTH_EMAIL_PROVIDER=brevo}
 * khiến OTP bị ghi vào log production thay vì gửi email thật.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "auth.email.provider", havingValue = "log", matchIfMissing = true)
public class LogEmailSender implements EmailSender {

	public LogEmailSender(Environment env) {
		for (String profile : env.getActiveProfiles()) {
			if ("prod".equalsIgnoreCase(profile)) {
				throw new IllegalStateException(
						"auth.email.provider=log không được phép ở profile 'prod' — set AUTH_EMAIL_PROVIDER=brevo (và BREVO_API_KEY) trước khi khởi động");
			}
		}
	}

	@Override
	public void send(EmailMessage message) {
		log.info("[DEV EMAIL] to={} | subject={}\n{}", message.to(), message.subject(), message.textBody());
	}
}
