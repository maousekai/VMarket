package com.vmarket.events;
/** version is monotonic per order; consumers must ignore older deliveries. */
public record DeliveryStatusChanged(String orderId, String status, long version) {}
