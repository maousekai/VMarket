package com.vmarket.notification.domain;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Tuỳ chọn kênh nhận thông báo của một người dùng (_id = userId). Nhóm không có = bật hết. */
@Document("notification_preferences")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationPreference {

	public record Channels(boolean email, boolean push) {
		public static final Channels ALL = new Channels(true, true);
	}

	@Id
	private String userId;

	@Builder.Default
	private Map<NotificationCategory, Channels> categories = new EnumMap<>(NotificationCategory.class);

	private Instant updatedAt;

	public Channels channels(NotificationCategory category) {
		return categories == null ? Channels.ALL : categories.getOrDefault(category, Channels.ALL);
	}
}
