package com.vmarket.payment.client;
import java.time.Instant;
public interface PayosClient {
    record Link(String paymentLinkId, String checkoutUrl, String qrCode) {}
    Link create(long orderCode, long amount, Instant expiresAt);
    void cancel(long orderCode);
}
