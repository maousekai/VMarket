package com.vmarket.order.event;

import jakarta.persistence.*;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "event_outbox")
@Getter
@Setter
@NoArgsConstructor
public class OutboxEvent {
    @Id
    @Column(length = 36)
    private String id = UUID.randomUUID().toString();

    @Column(nullable = false, length = 80)
    private String eventType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(length = 26)
    private String aggregateId;

    @Column(nullable = false)
    private int retryAttempts;

    @Column(nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(nullable = false, length = 10)
    private String state = "PENDING";

    @Column(length = 120)
    private String lastError;
}
