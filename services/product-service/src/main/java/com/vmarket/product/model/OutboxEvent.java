package com.vmarket.product.model;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Document("catalog_outbox")
@CompoundIndex(name = "outbox_pending_idx", def = "{'publishedAt': 1, 'nextAttemptAt': 1, 'claimedUntil': 1, 'createdAt': 1}")
public class OutboxEvent {
	@Id
	private String id;
	private String eventType;
	private Object payload;
	private Instant createdAt;
	private Instant nextAttemptAt;
	@Indexed(name = "outbox_published_ttl", expireAfter = "7d")
	private Instant publishedAt;
	private int attempts;
	private String lastError;
	private Instant deadAt;
	private String deadReason;
	private String claimedBy;
	private Instant claimedUntil;
	@Version
	private Long version;

	public OutboxEvent(String eventType, Object payload) {
		this.id = UUID.randomUUID().toString();
		this.eventType = eventType;
		this.payload = payload;
		this.createdAt = Instant.now();
		this.nextAttemptAt = this.createdAt;
	}
}
