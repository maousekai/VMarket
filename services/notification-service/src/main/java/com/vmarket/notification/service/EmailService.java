package com.vmarket.notification.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.github.f4b6a3.ulid.UlidCreator;
import com.vmarket.notification.client.InternalClients;
import com.vmarket.notification.client.UserContact;
import com.vmarket.notification.domain.EmailLog;
import com.vmarket.notification.email.EmailMessage;
import com.vmarket.notification.email.EmailSender;

import lombok.extern.slf4j.Slf4j;

/**
 * Gửi email giao dịch (FR-NOTI-02) với nhật ký {@link EmailLog}, chống gửi trùng khi
 * sự kiện bị gửi lại hoặc nhiều replica cùng xử lý.
 *
 * <p><b>Claim trước, gửi sau.</b> Trước khi gọi provider, tiến trình phải "giành" bản ghi
 * {@code EmailLog} của {@code dedupKey} sang trạng thái {@code SENDING}:
 * <ul>
 *   <li>Chưa có bản ghi → insert {@code SENDING} (unique index trên {@code dedupKey}). Insert
 *       thua ({@code DuplicateKeyException}) = tiến trình khác đã giữ.</li>
 *   <li>Đã có bản ghi → chỉ giành lại được bằng cập nhật có điều kiện khi đang {@code FAILED},
 *       hoặc {@code SENDING} quá {@link #STALE_CLAIM} (tiến trình trước chết giữa chừng).</li>
 * </ul>
 * Không giành được → return (đã {@code SENT}/{@code SKIPPED}, hoặc đang có người gửi).
 * Mọi cập nhật kết quả đều có điều kiện {@code status = SENDING}.
 *
 * <p>Không tra được email / email chưa xác thực / tài khoản bị khoá → {@code SKIPPED}
 * (không retry). Provider hoặc auth-service lỗi → {@code FAILED} rồi ném lại để event bus
 * retry / đưa vào DLQ.
 *
 * <p>Giới hạn còn lại: tiến trình chết SAU khi provider nhận email nhưng TRƯỚC khi ghi
 * {@code SENT} thì lần giành lại sau {@link #STALE_CLAIM} sẽ gửi thêm một lần — đánh đổi
 * có chủ ý để không mất email.
 */
@Slf4j
@Service
public class EmailService {

	/** Lớn hơn hẳn timeout gửi của provider (SMTP / Brevo tối đa ~10 giây). */
	static final Duration STALE_CLAIM = Duration.ofMinutes(1);

	private final MongoTemplate mongo;
	private final InternalClients clients;
	private final EmailSender sender;
	private final Clock clock;

	public EmailService(MongoTemplate mongo, InternalClients clients, EmailSender sender, Clock clock) {
		this.mongo = mongo;
		this.clients = clients;
		this.sender = sender;
		this.clock = clock;
	}

	public void send(String userId, String dedupKey, NotificationMessage message, String eventId) {
		EmailLog claimed = claim(userId, dedupKey, message, eventId);
		if (claimed == null) {
			return;
		}

		Optional<UserContact> contact;
		try {
			contact = clients.contact(userId);
		} catch (RuntimeException ex) {
			finish(dedupKey, EmailLog.Status.FAILED, null, "CONTACT_LOOKUP_FAILED: " + ex.getClass().getSimpleName());
			throw ex;
		}
		String skipReason = skipReason(contact);
		if (skipReason != null) {
			finish(dedupKey, EmailLog.Status.SKIPPED, null, skipReason);
			log.info("Bỏ qua email {} cho user {}: {}", message.type(), userId, skipReason);
			return;
		}

		String to = contact.get().email();
		NotificationMessage.Email email = message.email();
		try {
			sender.send(new EmailMessage(to, email.subject(), email.html(), email.text()));
		} catch (RuntimeException ex) {
			finish(dedupKey, EmailLog.Status.FAILED, to, ex.getMessage());
			throw ex;
		}
		finish(dedupKey, EmailLog.Status.SENT, to, null);
	}

	/** @return bản ghi đã giành được (trạng thái {@code SENDING}), hoặc {@code null}. */
	private EmailLog claim(String userId, String dedupKey, NotificationMessage message, String eventId) {
		Instant now = Instant.now(clock);
		try {
			return mongo.insert(EmailLog.builder()
					.id(UlidCreator.getMonotonicUlid().toString())
					.dedupKey(dedupKey)
					.userId(userId)
					.type(message.type())
					.subject(message.email().subject())
					.status(EmailLog.Status.SENDING)
					.provider(sender.provider())
					.attempts(1)
					.eventId(eventId)
					.createdAt(now)
					.updatedAt(now)
					.build());
		} catch (DuplicateKeyException alreadyClaimed) {
			Criteria reclaimable = new Criteria().orOperator(
					Criteria.where("status").is(EmailLog.Status.FAILED),
					Criteria.where("status").is(EmailLog.Status.SENDING).and("updatedAt").lt(now.minus(STALE_CLAIM)));
			return mongo.findAndModify(
					Query.query(Criteria.where("dedupKey").is(dedupKey)).addCriteria(reclaimable),
					new Update().set("status", EmailLog.Status.SENDING)
							.set("provider", sender.provider())
							.set("updatedAt", now)
							.inc("attempts", 1),
					FindAndModifyOptions.options().returnNew(true),
					EmailLog.class);
		}
	}

	private void finish(String dedupKey, EmailLog.Status status, String recipient, String error) {
		Instant now = Instant.now(clock);
		Update update = new Update().set("status", status).set("updatedAt", now).set("error", error);
		if (recipient != null) {
			update.set("recipient", recipient);
		}
		if (status == EmailLog.Status.SENT) {
			update.set("sentAt", now);
		}
		mongo.updateFirst(
				Query.query(Criteria.where("dedupKey").is(dedupKey).and("status").is(EmailLog.Status.SENDING)),
				update, EmailLog.class);
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
