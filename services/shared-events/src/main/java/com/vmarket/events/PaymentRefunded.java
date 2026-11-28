package com.vmarket.events;

/** Payment Service phat khi mot khoan hoan tien cho don da duoc xac nhan chuyen tra. */
public record PaymentRefunded(String orderId, long amount, String reason) {}
