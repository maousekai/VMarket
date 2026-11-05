package com.vmarket.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.vmarket.notification.client.InternalClients;
import com.vmarket.notification.client.UserContact;
import com.vmarket.notification.domain.EmailLog;
import com.vmarket.notification.domain.NotificationType;
import com.vmarket.notification.email.EmailDeliveryException;
import com.vmarket.notification.email.EmailMessage;
import com.vmarket.notification.email.EmailSender;
import com.vmarket.notification.repository.EmailLogRepository;

class EmailServiceTest {

	private final EmailLogRepository logs = mock(EmailLogRepository.class);
	private final InternalClients clients = mock(InternalClients.class);
	private final EmailSender sender = mock(EmailSender.class);
	private EmailService service;

	private final NotificationMessage message = new NotificationMessage(NotificationType.PAYMENT_SUCCEEDED, "t", "b",
			"/orders/o1", Map.of(), true, new NotificationMessage.Email("Thanh toán thành công", "<p>x</p>", "x"));

	@BeforeEach
	void setUp() {
		when(sender.provider()).thenReturn("log");
		when(logs.findByDedupKey(any())).thenReturn(Optional.empty());
		service = new EmailService(logs, clients, sender,
				Clock.fixed(Instant.parse("2026-11-05T10:00:00Z"), ZoneOffset.UTC));
	}

	@Test
	void sendsToVerifiedEmailAndLogsSent() {
		when(clients.contact("u1")).thenReturn(Optional.of(new UserContact("u1", "an@example.com", "an", true, false)));

		service.send("u1", "evt:u1:PAYMENT_SUCCEEDED", message, "evt");

		ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
		verify(sender).send(sent.capture());
		assertThat(sent.getValue().to()).isEqualTo("an@example.com");
		assertThat(sent.getValue().subject()).isEqualTo("Thanh toán thành công");

		ArgumentCaptor<EmailLog> saved = ArgumentCaptor.forClass(EmailLog.class);
		verify(logs).save(saved.capture());
		assertThat(saved.getValue().getStatus()).isEqualTo(EmailLog.Status.SENT);
		assertThat(saved.getValue().getRecipient()).isEqualTo("an@example.com");
		assertThat(saved.getValue().getAttempts()).isEqualTo(1);
		assertThat(saved.getValue().getSentAt()).isNotNull();
	}

	@Test
	void alreadySentEmailIsNotSentAgain() {
		when(logs.findByDedupKey("k")).thenReturn(Optional.of(EmailLog.builder().dedupKey("k")
				.status(EmailLog.Status.SENT).build()));

		service.send("u1", "k", message, "evt");

		verifyNoInteractions(clients);
		verify(sender, never()).send(any());
	}

	@Test
	void unverifiedOrMissingRecipientIsSkippedWithoutRetry() {
		when(clients.contact("u1")).thenReturn(Optional.of(new UserContact("u1", "an@example.com", "an", false, false)));
		when(clients.contact("ghost")).thenReturn(Optional.empty());

		service.send("u1", "k1", message, "evt");
		service.send("ghost", "k2", message, "evt");

		verify(sender, never()).send(any());
		ArgumentCaptor<EmailLog> saved = ArgumentCaptor.forClass(EmailLog.class);
		verify(logs, org.mockito.Mockito.times(2)).save(saved.capture());
		assertThat(saved.getAllValues()).extracting(EmailLog::getStatus).containsOnly(EmailLog.Status.SKIPPED);
		assertThat(saved.getAllValues()).extracting(EmailLog::getError)
				.containsExactly("EMAIL_NOT_VERIFIED", "USER_NOT_FOUND");
	}

	@Test
	void providerFailureIsLoggedAndRethrown() {
		when(clients.contact("u1")).thenReturn(Optional.of(new UserContact("u1", "an@example.com", "an", true, false)));
		doThrow(new EmailDeliveryException("Brevo trả lỗi 500")).when(sender).send(any());

		assertThatThrownBy(() -> service.send("u1", "k", message, "evt")).isInstanceOf(EmailDeliveryException.class);

		ArgumentCaptor<EmailLog> saved = ArgumentCaptor.forClass(EmailLog.class);
		verify(logs).save(saved.capture());
		assertThat(saved.getValue().getStatus()).isEqualTo(EmailLog.Status.FAILED);
		assertThat(saved.getValue().getError()).isEqualTo("Brevo trả lỗi 500");
	}

	@Test
	void retryAfterFailureReusesLogAndIncrementsAttempts() {
		EmailLog failed = EmailLog.builder().dedupKey("k").status(EmailLog.Status.FAILED).attempts(1).build();
		when(logs.findByDedupKey("k")).thenReturn(Optional.of(failed));
		when(clients.contact("u1")).thenReturn(Optional.of(new UserContact("u1", "an@example.com", "an", true, false)));

		service.send("u1", "k", message, "evt");

		verify(logs).save(failed);
		assertThat(failed.getStatus()).isEqualTo(EmailLog.Status.SENT);
		assertThat(failed.getAttempts()).isEqualTo(2);
	}
}
