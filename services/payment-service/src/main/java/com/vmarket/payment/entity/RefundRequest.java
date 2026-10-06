package com.vmarket.payment.entity;
import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import lombok.*;
@Entity @Table(name = "refund_requests") @Getter @Setter @NoArgsConstructor
public class RefundRequest {
    @Id @Column(length = 36) private String id = UUID.randomUUID().toString();
    @Column(nullable = false, unique = true) private Long paymentId;
    @Column(nullable = false) private long amount;
    @Column(nullable = false, length = 500) private String reason;
    @Column(nullable = false, length = 20) private String status = "PENDING";
    @Column(nullable = false) private Instant requestedAt;
    private Instant completedAt;
    @Column(length = 26) private String confirmedBy;
    @Column(length = 128) private String transferReference;
}
