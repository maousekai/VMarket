package com.vmarket.notification.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;

import org.springframework.stereotype.Service;

import com.vmarket.notification.domain.NotificationCategory;
import com.vmarket.notification.domain.NotificationPreference;
import com.vmarket.notification.domain.NotificationPreference.Channels;
import com.vmarket.notification.domain.NotificationType;
import com.vmarket.notification.dto.NotificationPreferencesDto;
import com.vmarket.notification.repository.NotificationPreferenceRepository;

/** Đọc / lưu tuỳ chọn email & push theo nhóm; mặc định bật mọi kênh. */
@Service
public class NotificationPreferenceService {

	private final NotificationPreferenceRepository repository;
	private final Clock clock;

	public NotificationPreferenceService(NotificationPreferenceRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	public NotificationPreferencesDto.Response get(String userId) {
		return toResponse(load(userId));
	}

	public NotificationPreferencesDto.Response update(String userId, NotificationPreferencesDto.Update request) {
		NotificationPreference preference = load(userId);
		EnumMap<NotificationCategory, Channels> categories = new EnumMap<>(NotificationCategory.class);
		if (preference.getCategories() != null) {
			categories.putAll(preference.getCategories());
		}
		for (NotificationPreferencesDto.CategoryUpdate item : request.categories()) {
			categories.put(item.key(), new Channels(item.email(), item.push()));
		}
		preference.setCategories(categories);
		preference.setUpdatedAt(Instant.now(clock));
		return toResponse(repository.save(preference));
	}

	public boolean allowsEmail(String userId, NotificationType type) {
		return load(userId).channels(NotificationCategory.of(type)).email();
	}

	public boolean allowsPush(String userId, NotificationType type) {
		return load(userId).channels(NotificationCategory.of(type)).push();
	}

	private NotificationPreference load(String userId) {
		return repository.findById(userId)
				.orElseGet(() -> NotificationPreference.builder().userId(userId).build());
	}

	private static NotificationPreferencesDto.Response toResponse(NotificationPreference preference) {
		return new NotificationPreferencesDto.Response(Arrays.stream(NotificationCategory.values())
				.map(category -> {
					Channels channels = preference.channels(category);
					return new NotificationPreferencesDto.Category(category.name(), category.label(),
							channels.email(), channels.push());
				})
				.toList());
	}
}
