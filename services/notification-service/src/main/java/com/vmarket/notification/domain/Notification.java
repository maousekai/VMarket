package com.vmarket.notification.domain;

import java.time.Instant;
import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Thông báo trong ứng dụng (FR-NOTI-01) — collection {@code notifications}.
 *
 * <p>{@code dedupKey = eventId:recipientId:type} có unique index: sự kiện bị RabbitMQ
 * gửi lại (at-least-once) không sinh thông báo trùng.
 */
@Document("notifications")
@CompoundIndexes({
		@CompoundIndex(name = "user_created", def = "{'userId': 1, 'createdAt': -1}"),
		@CompoundIndex(name = "user_read", def = "{'userId': 1, 'read': 1}")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Notification {

	@Id
	private String id;

	@Indexed(unique = true)
	private String dedupKey;

	private String userId;

	private NotificationType type;

	private String title;

	private String message;

	/** Đường dẫn tương đối trên frontend để mở khi bấm thông báo (vd {@code /orders/123}). */
	private String link;

	/** Dữ liệu tham chiếu (orderId, shopId...) cho client. */
	private Map<String, String> data;

	private boolean read;

	private Instant readAt;

	private Instant createdAt;

	/** eventId của sự kiện nguồn — truy vết. */
	private String eventId;
}
