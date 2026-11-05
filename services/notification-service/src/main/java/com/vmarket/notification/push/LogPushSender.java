package com.vmarket.notification.push;

import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/** Dev/test: không gọi FCM, chỉ ghi log (không ghi token đầy đủ). */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.notification.push.provider", havingValue = "log", matchIfMissing = true)
public class LogPushSender implements PushSender {

	@Override
	public Result send(String deviceToken, String title, String body, Map<String, String> data) {
		String shortToken = deviceToken.length() > 8 ? deviceToken.substring(0, 8) + "…" : deviceToken;
		log.info("[DEV PUSH] token={} | {} - {} | data={}", shortToken, title, body, data);
		return Result.SENT;
	}
}
