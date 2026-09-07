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
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "auth.email.provider", havingValue = "log", matchIfMissing = true)
public class LogEmailSender implements EmailSender {

	public LogEmailSender(Environment env) {
		for (String profile : env.getActiveProfiles()) {
			if ("prod".equalsIgnoreCase(profile)) {
				log.warn("auth.email.provider=log ở profile 'prod' — email (và mã OTP) chỉ được ghi log, KHÔNG gửi đi!");
			}
		}
	}

	@Override
	public void send(EmailMessage message) {
		log.info("[DEV EMAIL] to={} | subject={}\n{}", message.to(), message.subject(), message.textBody());
	}
}
