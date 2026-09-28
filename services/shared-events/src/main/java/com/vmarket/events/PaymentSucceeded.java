package com.vmarket.events;

/** Payment Service phát sau khi giao dịch online thành công. */
public record PaymentSucceeded(String orderId) {
}
