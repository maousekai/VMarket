package com.vmarket.notification.service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.github.f4b6a3.ulid.UlidCreator;
import com.vmarket.events.EventEnvelope;
import com.vmarket.notification.domain.Notification;
import com.vmarket.notification.repository.NotificationRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Gửi {@link NotificationMessage} tới người nhận qua 3 kênh, chống trùng khi sự kiện bị
 * gửi lại (RabbitMQ at-least-once):
 * <ol>
 *   <li><b>In-app</b> — insert theo {@code dedupKey} (unique index). Đã có → bỏ qua.</li>
 *   <li><b>Push</b> — chỉ gửi khi in-app vừa được tạo lần đầu; lỗi push không làm hỏng
 *       sự kiện (best-effort, mức ưu tiên TB).</li>
 *   <li><b>Email</b> — chống trùng riêng trong {@link EmailService}; lỗi gửi được ném ra để
 *       event bus retry. Lần retry bỏ qua in-app/push đã xong, chỉ gửi lại email.</li>
 * </ol>
 *
 * <p>{@code dedupKey = eventId:recipientId:type[:shopId]} — thông báo theo gian hàng mang
 * thêm {@code shopId} để chủ của hai gian hàng trong cùng một đơn nhận đủ hai thông báo.
 */
@Slf4j
@Service
public class NotificationDispatcher {

	/** Một người nhận và nội dung dành cho người đó. */
	public record Delivery(String recipientId, NotificationMessage message) {
	}

	private final NotificationRepository notifications;
	private final PushService pushService;
	private final EmailService emailService;
	private final NotificationPreferenceService preferences;
	private final Clock clock;

	public NotificationDispatcher(NotificationRepository notifications, PushService pushService,
			EmailService emailService, NotificationPreferenceService preferences, Clock clock) {
		this.notifications = notifications;
		this.pushService = pushService;
		this.emailService = emailService;
		this.preferences = preferences;
		this.clock = clock;
	}

	/**
	 * Gửi cho TẤT CẢ người nhận, lỗi của người này không chặn người khác. Sau khi xử lý hết
	 * mới ném lỗi đầu tiên để event bus retry; lần retry bỏ qua những người đã xong nhờ dedup.
	 */
	public void deliverAll(List<Delivery> deliveries, EventEnvelope envelope) {
		deliverAll(deliveries, envelope, null);
	}

	/**
	 * Như {@link #deliverAll(List, EventEnvelope)}, kèm một lỗi xảy ra TRƯỚC khi gửi (vd tra
	 * chủ gian hàng thất bại): vẫn gửi hết danh sách hiện có rồi mới ném lỗi đó để retry.
	 */
	public void deliverAll(List<Delivery> deliveries, EventEnvelope envelope, RuntimeException earlierFailure) {
		RuntimeException first = earlierFailure;
		for (Delivery delivery : deliveries) {
			try {
				deliver(delivery.recipientId(), delivery.message(), envelope);
			} catch (RuntimeException ex) {
				log.warn("Gửi {} cho {} (sự kiện {}) thất bại: {}", delivery.message().type(), delivery.recipientId(),
						envelope.eventId(), ex.getMessage());
				if (first == null) {
					first = ex;
				}
			}
		}
		if (first != null) {
			throw first;
		}
	}

	public void deliver(String recipientId, NotificationMessage message, EventEnvelope envelope) {
		if (recipientId == null || recipientId.isBlank()) {
			log.warn("Bỏ qua thông báo {} của sự kiện {}: thiếu người nhận", message.type(), envelope.eventId());
			return;
		}
		String dedupKey = dedupKey(recipientId, message, envelope);
		boolean created = saveInApp(recipientId, dedupKey, message, envelope);
		// Thông báo trong ứng dụng luôn được lưu; email / push theo tuỳ chọn của người nhận.
		if (created && message.push() && preferences.allowsPush(recipientId, message.type())) {
			pushService.sendToUser(recipientId, message.title(), message.body(), pushData(message));
		}
		if (message.email() != null && preferences.allowsEmail(recipientId, message.type())) {
			emailService.send(recipientId, dedupKey, message, envelope.eventId());
		}
	}

	static String dedupKey(String recipientId, NotificationMessage message, EventEnvelope envelope) {
		String key = envelope.eventId() + ":" + recipientId + ":" + message.type();
		String shopId = message.data() == null ? null : message.data().get("shopId");
		return shopId == null || shopId.isBlank() ? key : key + ":" + shopId;
	}

	private boolean saveInApp(String recipientId, String dedupKey, NotificationMessage message,
			EventEnvelope envelope) {
		// Fast path: redelivery thường gặp, tránh một lần insert thất bại. Tính đúng đắn
		// vẫn do unique index + catch DuplicateKeyException bên dưới đảm bảo.
		if (notifications.existsByDedupKey(dedupKey)) {
			return false;
		}
		try {
			notifications.insert(Notification.builder()
					.id(UlidCreator.getMonotonicUlid().toString())
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
			// Tiến trình khác xử lý cùng sự kiện song song — bản kia đã ghi.
			return false;
		}
	}

	private Map<String, String> pushData(NotificationMessage message) {
		Map<String, String> data = new HashMap<>();
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
