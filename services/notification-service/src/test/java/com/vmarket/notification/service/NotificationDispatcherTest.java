package com.vmarket.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import com.vmarket.events.EventEnvelope;
import com.vmarket.notification.domain.Notification;
import com.vmarket.notification.domain.NotificationType;
import com.vmarket.notification.email.EmailDeliveryException;
import com.vmarket.notification.repository.NotificationRepository;

class NotificationDispatcherTest {

	private final NotificationRepository repository = mock(NotificationRepository.class);
	private final PushService push = mock(PushService.class);
	private final EmailService email = mock(EmailService.class);
	private final Clock clock = Clock.fixed(Instant.parse("2026-11-05T10:00:00Z"), ZoneOffset.UTC);
	private NotificationDispatcher dispatcher;

	private final EventEnvelope envelope = new EventEnvelope("evt-1", "OrderPlaced", 1L, Map.of());
	private final NotificationMessage withEmail = new NotificationMessage(NotificationType.ORDER_PLACED, "Tiêu đề",
			"Nội dung", "/orders/o1", Map.of("orderId", "o1"), true,
			new NotificationMessage.Email("subject", "<p>html</p>", "text"));

	@BeforeEach
	void setUp() {
		dispatcher = new NotificationDispatcher(repository, push, email, clock);
	}

	@Test
	void firstDeliveryStoresInAppThenPushesAndEmails() {
		when(repository.existsByDedupKey("evt-1:buyer-1:ORDER_PLACED")).thenReturn(false);

		dispatcher.deliver("buyer-1", withEmail, envelope);

		ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
		verify(repository).insert(saved.capture());
		assertThat(saved.getValue().getUserId()).isEqualTo("buyer-1");
		assertThat(saved.getValue().getDedupKey()).isEqualTo("evt-1:buyer-1:ORDER_PLACED");
		assertThat(saved.getValue().isRead()).isFalse();
		assertThat(saved.getValue().getCreatedAt()).isEqualTo(Instant.parse("2026-11-05T10:00:00Z"));

		ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.captor();
		verify(push).sendToUser(eq("buyer-1"), eq("Tiêu đề"), eq("Nội dung"), data.capture());
		assertThat(data.getValue()).containsEntry("type", "ORDER_PLACED").containsEntry("orderId", "o1")
				.containsEntry("link", "/orders/o1");
		verify(email).send("buyer-1", "evt-1:buyer-1:ORDER_PLACED", withEmail, "evt-1");
	}

	@Test
	void redeliveredEventSkipsInAppAndPushButRetriesEmail() {
		when(repository.existsByDedupKey(any())).thenReturn(true);

		dispatcher.deliver("buyer-1", withEmail, envelope);

		verify(repository, never()).insert(any(Notification.class));
		verify(push, never()).sendToUser(any(), any(), any(), anyMap());
		// EmailService tự chống trùng qua EmailLog; vẫn được gọi để gửi lại email từng lỗi.
		verify(email).send("buyer-1", "evt-1:buyer-1:ORDER_PLACED", withEmail, "evt-1");
	}

	@Test
	void concurrentDuplicateInsertDoesNotPushTwice() {
		when(repository.existsByDedupKey(any())).thenReturn(false);
		when(repository.insert(any(Notification.class))).thenThrow(new DuplicateKeyException("dup"));

		dispatcher.deliver("buyer-1", withEmail, envelope);

		verify(push, never()).sendToUser(any(), any(), any(), anyMap());
	}

	@Test
	void emailFailurePropagatesSoEventBusRetries() {
		when(repository.existsByDedupKey(any())).thenReturn(false);
		org.mockito.Mockito.doThrow(new EmailDeliveryException("smtp down")).when(email)
				.send(any(), any(), any(), any());

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> dispatcher.deliver("buyer-1", withEmail, envelope))
				.isInstanceOf(EmailDeliveryException.class);
		verify(repository).insert(any(Notification.class));
	}

	@Test
	void messageWithoutEmailOrPushOnlyStoresInApp() {
		NotificationMessage inAppOnly = new NotificationMessage(NotificationType.ORDER_DELIVERED, "t", "b", null,
				Map.of(), false, null);
		dispatcher.deliver("seller-1", inAppOnly, envelope);

		verify(repository).insert(any(Notification.class));
		verifyNoInteractions(push, email);
	}

	@Test
	void blankRecipientIsIgnored() {
		dispatcher.deliver(" ", withEmail, envelope);
		dispatcher.deliver(null, withEmail, envelope);

		verifyNoInteractions(repository, push, email);
	}
}
