package com.vmarket.notification.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.vmarket.events.EventEnvelope;
import com.vmarket.notification.domain.Notification;
import com.vmarket.notification.repository.NotificationRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Gửi một {@link NotificationMessage} tới một người nhận qua 3 kênh, chống trùng khi
 * sự kiện bị gửi lại (RabbitMQ at-least-once):
 * <ol>
 *   <li><b>In-app</b> — insert theo {@code dedupKey} (unique index). Đã có → bỏ qua.</li>
 *   <li><b>Push</b> — chỉ gửi khi in-app vừa được tạo lần đầu; lỗi push không làm hỏng
 *       sự kiện (best-effort, mức ưu tiên TB).</li>
 *   <li><b>Email</b> — chống trùng riêng qua {@code EmailLog}; lỗi gửi được ném ra để
 *       event bus retry. Lần retry bỏ qua in-app/push đã xong, chỉ gửi lại email.</li>
 * </ol>
 */
@Slf4j
@Service
public class NotificationDispatcher {

	private final NotificationRepository notifications;
	private final PushService pushService;
	private final EmailService emailService;
	private final Clock clock;

	public NotificationDispatcher(NotificationRepository notifications, PushService pushService, EmailService emailService,
			Clock clock) {
		this.notifications = notifications;
		this.pushService = pushService;
		this.emailService = emailService;
		this.clock = clock;
	}

	public void deliver(String recipientId, NotificationMessage message, EventEnvelope envelope) {
		if (recipientId == null || recipientId.isBlank()) {
			log.warn("Bỏ qua thông báo {} của sự kiện {}: thiếu người nhận", message.type(), envelope.eventId());
			return;
		}
		String dedupKey = envelope.eventId() + ":" + recipientId + ":" + message.type();
		boolean created = saveInApp(recipientId, dedupKey, message, envelope);
		if (created && message.push()) {
			pushService.sendToUser(recipientId, message.title(), message.body(), pushData(message));
		}
		if (message.email() != null) {
			emailService.send(recipientId, dedupKey, message, envelope.eventId());
		}
	}

	private boolean saveInApp(String recipientId, String dedupKey, NotificationMessage message,
			EventEnvelope envelope) {
		if (notifications.existsByDedupKey(dedupKey)) {
			return false;
		}
		try {
			notifications.insert(Notification.builder()
					.dedupKey(dedupKey)
					.userId(recipientId)
					.type(message.type())
					.title(message.title())
					.message(message.body())
					.link(message.link())
					.data(message.data())
					.read(false)
					.createdAt(Instant.now(clock))
					.eventId(envelope.eventId())
					.build());
			return true;
		} catch (DuplicateKeyException ex) {
			// Hai consumer xử lý cùng sự kiện song song — bản kia đã ghi.
			return false;
		}
	}

	private Map<String, String> pushData(NotificationMessage message) {
		Map<String, String> data = new java.util.HashMap<>();
		if (message.data() != null) {
			data.putAll(message.data());
		}
		data.put("type", message.type().name());
		if (message.link() != null) {
			data.put("link", message.link());
		}
		return data;
	}
}
