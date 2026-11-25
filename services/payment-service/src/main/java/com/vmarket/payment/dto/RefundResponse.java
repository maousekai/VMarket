package com.vmarket.payment.dto;

import com.vmarket.payment.entity.RefundRequest;

import java.time.Instant;

/** Khoản hoàn tiền người mua được xem (không lộ admin đã xác nhận). */
public record RefundResponse(
        String id,
        Long paymentId,
        String returnId,
        long amount,
        String reason,
        String status,
        Instant requestedAt,
        Instant completedAt,
        String transferReference) {
    public static RefundResponse from(RefundRequest r) {
        return new RefundResponse(
                r.getId(),
                r.getPaymentId(),
                r.getReturnId(),
                r.getAmount(),
                r.getReason(),
                r.getStatus(),
                r.getRequestedAt(),
                r.getCompletedAt(),
                r.getTransferReference());
    }
}
