package com.vmarket.notification.dto;

import java.util.List;

import com.vmarket.notification.domain.NotificationCategory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request/response của GET/PUT /api/notifications/preferences. */
public final class NotificationPreferencesDto {

	private NotificationPreferencesDto() {
	}

	public record Category(String key, String label, boolean email, boolean push) {
	}

	public record Response(List<Category> categories) {
	}

	public record CategoryUpdate(@NotNull NotificationCategory key, boolean email, boolean push) {
	}

	public record Update(@NotNull @Size(max = 10) List<@Valid @NotNull CategoryUpdate> categories) {
	}
}
