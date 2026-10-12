package com.vmarket.payment.client;
import java.time.Instant;
public interface PayosClient {
    enum Status { PENDING, PROCESSING, PAID, CANCELLED, EXPIRED }
    record Link(String paymentLinkId, String checkoutUrl, String qrCode, Status status) {
        public Link(String paymentLinkId, String checkoutUrl, String qrCode) {
            this(paymentLinkId, checkoutUrl, qrCode, Status.PENDING);
        }
    }
    Link create(long orderCode, long amount, Instant expiresAt);
    void cancel(long orderCode);
}
