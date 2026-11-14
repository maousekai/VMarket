package com.vmarket.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.vmarket.notification.domain.NotificationCategory;
import com.vmarket.notification.domain.NotificationPreference;
import com.vmarket.notification.domain.NotificationPreference.Channels;
import com.vmarket.notification.domain.NotificationType;
import com.vmarket.notification.dto.NotificationPreferencesDto;
import com.vmarket.notification.repository.NotificationPreferenceRepository;

class NotificationPreferenceServiceTest {

	private final NotificationPreferenceRepository repository = mock(NotificationPreferenceRepository.class);
	private final Clock clock = Clock.fixed(Instant.parse("2026-11-10T10:00:00Z"), ZoneOffset.UTC);
	private final NotificationPreferenceService service = new NotificationPreferenceService(repository, clock);

	@Test
	void everyChannelIsOnByDefault() {
		when(repository.findById("u1")).thenReturn(Optional.empty());

		var response = service.get("u1");

		assertThat(response.categories()).extracting(NotificationPreferencesDto.Category::key)
				.containsExactly("ORDER", "PAYMENT", "SHOP");
		assertThat(response.categories()).allMatch(c -> c.email() && c.push());
		assertThat(service.allowsEmail("u1", NotificationType.PAYMENT_SUCCEEDED)).isTrue();
		assertThat(service.allowsPush("u1", NotificationType.SHOP_SUSPENDED)).isTrue();
	}

	@Test
	void updateKeepsOtherCategoriesAndAppliesPerType() {
		Map<NotificationCategory, Channels> stored = new EnumMap<>(NotificationCategory.class);
		stored.put(NotificationCategory.SHOP, new Channels(false, true));
		when(repository.findById("u1")).thenReturn(Optional.of(
				NotificationPreference.builder().userId("u1").categories(stored).build()));
		when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		var response = service.update("u1", new NotificationPreferencesDto.Update(List.of(
				new NotificationPreferencesDto.CategoryUpdate(NotificationCategory.ORDER, false, false))));

		ArgumentCaptor<NotificationPreference> saved = ArgumentCaptor.forClass(NotificationPreference.class);
		verify(repository).save(saved.capture());
		assertThat(saved.getValue().getUpdatedAt()).isEqualTo(clock.instant());
		assertThat(saved.getValue().channels(NotificationCategory.SHOP)).isEqualTo(new Channels(false, true));
		assertThat(saved.getValue().channels(NotificationCategory.ORDER)).isEqualTo(new Channels(false, false));
		assertThat(response.categories().get(0)).isEqualTo(
				new NotificationPreferencesDto.Category("ORDER", "Đơn hàng & giao hàng", false, false));

		when(repository.findById("u1")).thenReturn(Optional.of(saved.getValue()));
		assertThat(service.allowsEmail("u1", NotificationType.NEW_ORDER)).isFalse();
		assertThat(service.allowsPush("u1", NotificationType.DELIVERY_ASSIGNED)).isFalse();
		assertThat(service.allowsEmail("u1", NotificationType.RETURN_RESOLVED)).isTrue();
		assertThat(service.allowsEmail("u1", NotificationType.PRODUCT_REMOVED)).isFalse();
	}

	@Test
	void everyNotificationTypeBelongsToACategory() {
		for (NotificationType type : NotificationType.values()) {
			assertThat(NotificationCategory.of(type)).isNotNull();
		}
	}
}
