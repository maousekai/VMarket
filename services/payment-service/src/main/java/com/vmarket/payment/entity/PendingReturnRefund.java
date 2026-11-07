package com.vmarket.payment.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "pending_return_refunds")
@Getter
@Setter
@NoArgsConstructor
public class PendingReturnRefund {

    @Id
    @Column(length = 36)
    private String id = UUID.randomUUID().toString();

    @Column(nullable = false, length = 26)
    private String orderId;

    @Column(nullable = false, unique = true, length = 36)
    private String returnId;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private boolean processed = false;

    public PendingReturnRefund(
            String orderId, String returnId, long amount, String reason, Instant createdAt) {
        this.orderId = orderId;
        this.returnId = returnId;
        this.amount = amount;
        this.reason = reason;
        this.createdAt = createdAt;
        this.processed = false;
    }
}

