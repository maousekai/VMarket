package com.vmarket.payment.entity;

import jakarta.persistence.*;

import lombok.*;

import java.time.Instant;

@Entity
@Table(
        name = "payment_transactions",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_payment_method_reference",
                        columnNames = {"method", "reference"}))
@Getter
@Setter
@NoArgsConstructor
public class PaymentTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "payment_code")
    @SequenceGenerator(name = "payment_code", sequenceName = "payment_code_seq", allocationSize = 1)
    private Long id;

    @Column(nullable = false, unique = true, length = 26)
    private String orderId;

    @Column(nullable = false, length = 26)
    private String buyerId;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 10)
    private String method;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 64)
    private String paymentLinkId;

    @Column(length = 2048)
    private String checkoutUrl;

    @Column(length = 4096)
    private String qrCode;

    @Column(length = 128)
    private String reference;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant expiresAt;
    private Instant linkExpiresAt;
    private Instant settlementExpiresAt;

    @Column(length = 36)
    private String linkRequestToken;

    private Instant linkRequestExpiresAt;
    private boolean linkCreationUncertain;

    @Column(length = 36)
    private String cancelRequestToken;

    @Column(length = 120)
    private String cancellationError;

    private Instant paidAt;
    private boolean cancelPending;
    private Instant nextCancelAttemptAt;
    private int cancelAttempts;
    private boolean reconciliationRequired;
}
