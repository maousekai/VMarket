package com.vmarket.notification.service;

import java.util.Map;

import com.vmarket.notification.domain.NotificationType;

/**
 * Nội dung một thông báo cho MỘT người nhận, kèm kênh cần gửi.
 *
 * @param type  loại thông báo
 * @param title tiêu đề ngắn (in-app + push)
 * @param body  nội dung (in-app + push)
 * @param link  đường dẫn tương đối trên frontend
 * @param data  tham chiếu (orderId, shopId...)
 * @param push  có gửi push FCM không
 * @param email email giao dịch kèm theo, {@code null} = không gửi email
 */
public record NotificationMessage(
		NotificationType type,
		String title,
		String body,
		String link,
		Map<String, String> data,
		boolean push,
		Email email) {

	/** Nội dung email; HTML đã escape dữ liệu động. */
	public record Email(String subject, String html, String text) {
	}
}
