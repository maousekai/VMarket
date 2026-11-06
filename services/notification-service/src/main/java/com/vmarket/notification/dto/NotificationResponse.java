package com.vmarket.notification.dto;

import java.time.Instant;
import java.util.Map;

import com.vmarket.notification.domain.Notification;
import com.vmarket.notification.domain.NotificationType;

/** Một thông báo trong trung tâm thông báo. */
public record NotificationResponse(
		String id,
		NotificationType type,
		String title,
		String message,
		String link,
		Map<String, String> data,
		boolean read,
		Instant readAt,
		Instant createdAt) {

	public static NotificationResponse from(Notification n) {
		return new NotificationResponse(n.getId(), n.getType(), n.getTitle(), n.getMessage(), n.getLink(),
				n.getData() == null ? Map.of() : n.getData(), n.isRead(), n.getReadAt(), n.getCreatedAt());
	}
}
