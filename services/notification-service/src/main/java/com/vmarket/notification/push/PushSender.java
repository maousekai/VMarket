package com.vmarket.notification.push;

import java.util.Map;

/**
 * Cổng gửi push notification tới một thiết bị (FR-NOTI-03). Chọn implementation bằng
 * {@code app.notification.push.provider}: {@code log} (mặc định) | {@code fcm}.
 */
public interface PushSender {

	enum Result {
		/** Đã gửi. */
		SENT,
		/** Token không còn hợp lệ (app gỡ / token hết hạn) — nên xoá khỏi CSDL. */
		INVALID_TOKEN,
		/** Lỗi tạm thời — giữ token. */
		FAILED
	}

	Result send(String deviceToken, String title, String body, Map<String, String> data);
}
