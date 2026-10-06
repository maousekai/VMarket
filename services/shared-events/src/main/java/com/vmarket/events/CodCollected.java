package com.vmarket.events;

/** Delivery emits this after verifying the assigned shipper collected the server-side order amount. */
public record CodCollected(String orderId, String buyerId, long amount, String reference) {}
