package com.vmarket.payment.client;

import java.time.Instant;

public interface PayosClient {
    enum Status {
        PENDING,
        PROCESSING,
        PAID,
        CANCELLED,
        EXPIRED
    }

    record Link(String paymentLinkId, String checkoutUrl, String qrCode, Status status) {
        public Link(String paymentLinkId, String checkoutUrl, String qrCode) {
            this(paymentLinkId, checkoutUrl, qrCode, Status.PENDING);
        }
    }

    /** Signed state of a payment link; reference/time come from its first bank transaction. */
    record Info(
            Status status,
            String paymentLinkId,
            long amount,
            String reference,
            String transactionDateTime) {}

    Link create(long orderCode, long amount, Instant expiresAt);

    /** Reads and verifies the provider's current state (used when webhooks cannot reach us). */
    default Info info(long orderCode) {
        throw new UnsupportedOperationException("info");
    }

    void cancel(long orderCode);
}
