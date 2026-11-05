package com.vmarket.notification.email;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Dev/test: KHÔNG gửi email thật, chỉ ghi log. Ở profile {@code prod} bị coi là lỗi
 * khởi động để không quên cấu hình provider thật.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.notification.email.provider", havingValue = "log", matchIfMissing = true)
public class LogEmailSender implements EmailSender {

	public LogEmailSender(Environment env) {
		for (String profile : env.getActiveProfiles()) {
			if ("prod".equalsIgnoreCase(profile)) {
				throw new IllegalStateException(
						"app.notification.email.provider=log không được phép ở profile 'prod' — set NOTIFICATION_EMAIL_PROVIDER=smtp|brevo");
			}
		}
	}

	@Override
	public String provider() {
		return "log";
	}

	@Override
	public void send(EmailMessage message) {
		log.info("[DEV EMAIL] to={} | subject={}\n{}", message.to(), message.subject(), message.textBody());
	}
}
