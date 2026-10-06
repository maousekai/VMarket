package com.vmarket.payment.entity;
import java.time.Instant;
import jakarta.persistence.*;
import lombok.*;
@Entity @Table(name = "payment_transactions") @Getter @Setter @NoArgsConstructor
public class PaymentTransaction {
    @Id @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "payment_code")
    @SequenceGenerator(name = "payment_code", sequenceName = "payment_code_seq", allocationSize = 1)
    private Long id;
    @Column(nullable = false, unique = true, length = 26) private String orderId;
    @Column(nullable = false, length = 26) private String buyerId;
    @Column(nullable = false) private long amount;
    @Column(nullable = false, length = 10) private String method;
    @Column(nullable = false, length = 20) private String status;
    @Column(length = 64) private String paymentLinkId;
    @Column(length = 2048) private String checkoutUrl;
    @Column(length = 4096) private String qrCode;
    @Column(length = 128, unique = true) private String reference;
    @Column(nullable = false) private Instant createdAt;
    private Instant expiresAt;
    private Instant paidAt;
    private boolean cancelPending;
    private Instant nextCancelAttemptAt;
    private int cancelAttempts;
    private boolean reconciliationRequired;
}
