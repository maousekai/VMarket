package com.vmarket.payment.event;
import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import lombok.*;
@Entity @Table(name = "event_outbox") @Getter @Setter @NoArgsConstructor
public class OutboxEvent {
    @Id @Column(length = 36) private String id = UUID.randomUUID().toString();
    @Column(nullable = false, length = 80) private String eventType;
    @Column(nullable = false, columnDefinition = "TEXT") private String payload;
    @Column(nullable = false) private Instant createdAt = Instant.now();
}
