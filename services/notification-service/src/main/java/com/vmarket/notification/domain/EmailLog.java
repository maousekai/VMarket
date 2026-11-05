package com.vmarket.notification.domain;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Nhật ký email giao dịch (FR-NOTI-02) — collection {@code email_logs}.
 *
 * <p>Một bản ghi cho mỗi {@code dedupKey}: lần gửi lại sau lỗi cập nhật chính bản ghi
 * đó ({@code attempts}++), email đã {@code SENT} thì không gửi lần hai.
 */
@Document("email_logs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailLog {

	public enum Status {
		SENT, FAILED, SKIPPED
	}

	@Id
	private String id;

	@Indexed(unique = true)
	private String dedupKey;

	@Indexed
	private String userId;

	/** Địa chỉ nhận (null nếu bỏ qua vì không tra được email). */
	private String recipient;

	private NotificationType type;

	private String subject;

	private Status status;

	private String provider;

	/** Lý do lỗi / bỏ qua — không chứa nội dung email. */
	private String error;

	private int attempts;

	private String eventId;

	private Instant createdAt;

	private Instant updatedAt;

	private Instant sentAt;
}
