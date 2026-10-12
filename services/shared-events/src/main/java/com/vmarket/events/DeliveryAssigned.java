package com.vmarket.events;
public record DeliveryAssigned(String assignmentId, String orderId, String buyerId, String shipperId) {}
