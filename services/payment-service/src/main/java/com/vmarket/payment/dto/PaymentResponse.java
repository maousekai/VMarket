package com.vmarket.payment.dto;

import com.vmarket.payment.entity.PaymentTransaction;

import java.time.Instant;

public record PaymentResponse(
        Long id,
        String orderId,
        long amount,
        String method,
        String status,
        String checkoutUrl,
        String qrCode,
        boolean qrAvailable,
        boolean reconciliationRequired,
        Instant expiresAt,
        Instant linkExpiresAt,
        Instant settlementExpiresAt,
        Instant paidAt) {
    public static PaymentResponse from(PaymentTransaction p) {
        return new PaymentResponse(
                p.getId(),
                p.getOrderId(),
                p.getAmount(),
                p.getMethod(),
                p.getStatus(),
                p.getCheckoutUrl(),
                p.getQrCode(),
                p.getQrCode() != null && !p.getQrCode().isBlank(),
                p.isReconciliationRequired(),
                p.getExpiresAt(),
                p.getLinkExpiresAt(),
                p.getSettlementExpiresAt(),
                p.getPaidAt());
    }
}
