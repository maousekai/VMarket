package com.vmarket.events;
/** Terminal payment failure; expiry cancels only orders still waiting for payment. */
public record PaymentFailed(String orderId, String reason) {}
