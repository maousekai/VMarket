package com.vmarket.notification.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.vmarket.notification.client.InternalClients;
import com.vmarket.notification.client.UserContact;
import com.vmarket.notification.domain.EmailLog;
import com.vmarket.notification.email.EmailMessage;
import com.vmarket.notification.email.EmailSender;
import com.vmarket.notification.repository.EmailLogRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Gửi email giao dịch (FR-NOTI-02) và ghi {@link EmailLog}.
 *
 * <ul>
 *   <li>Đã {@code SENT} cho cùng {@code dedupKey} → không gửi lại.</li>
 *   <li>Không tra được email / email chưa xác thực / tài khoản bị khoá → {@code SKIPPED},
 *       không ném lỗi (thử lại cũng không đổi kết quả).</li>
 *   <li>Provider lỗi → {@code FAILED} rồi ném lại để event bus retry / đưa vào DLQ.</li>
 * </ul>
 */
@Slf4j
@Service
public class EmailService {

	private final EmailLogRepository logs;
	private final InternalClients clients;
	private final EmailSender sender;
	private final Clock clock;

	public EmailService(EmailLogRepository logs, InternalClients clients, EmailSender sender, Clock clock) {
		this.logs = logs;
		this.clients = clients;
		this.sender = sender;
		this.clock = clock;
	}

	public void send(String userId, String dedupKey, NotificationMessage message, String eventId) {
		Instant now = Instant.now(clock);
		EmailLog entry = logs.findByDedupKey(dedupKey).orElseGet(() -> EmailLog.builder()
				.dedupKey(dedupKey)
				.userId(userId)
				.type(message.type())
				.subject(message.email().subject())
				.eventId(eventId)
				.createdAt(now)
				.build());
		if (entry.getStatus() == EmailLog.Status.SENT || entry.getStatus() == EmailLog.Status.SKIPPED) {
			return;
		}
		entry.setProvider(sender.provider());
		entry.setUpdatedAt(now);

		Optional<UserContact> contact = clients.contact(userId);
		String skipReason = skipReason(contact);
		if (skipReason != null) {
			entry.setStatus(EmailLog.Status.SKIPPED);
			entry.setError(skipReason);
			logs.save(entry);
			log.info("Bỏ qua email {} cho user {}: {}", message.type(), userId, skipReason);
			return;
		}

		String to = contact.get().email();
		entry.setRecipient(to);
		entry.setAttempts(entry.getAttempts() + 1);
		try {
			NotificationMessage.Email email = message.email();
			sender.send(new EmailMessage(to, email.subject(), email.html(), email.text()));
			entry.setStatus(EmailLog.Status.SENT);
			entry.setError(null);
			entry.setSentAt(Instant.now(clock));
			logs.save(entry);
		} catch (RuntimeException ex) {
			entry.setStatus(EmailLog.Status.FAILED);
			entry.setError(ex.getMessage());
			logs.save(entry);
			throw ex;
		}
	}

	private String skipReason(Optional<UserContact> contact) {
		if (contact.isEmpty()) {
			return "USER_NOT_FOUND";
		}
		if (contact.get().suspended()) {
			return "ACCOUNT_SUSPENDED";
		}
		if (!StringUtils.hasText(contact.get().email())) {
			return "NO_EMAIL";
		}
		if (!contact.get().emailVerified()) {
			return "EMAIL_NOT_VERIFIED";
		}
		return null;
	}
}
