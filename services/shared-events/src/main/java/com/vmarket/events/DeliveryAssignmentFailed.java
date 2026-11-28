package com.vmarket.events;

/** Delivery Service phat khi tu dong phan cong khong tim duoc shipper: admin can xu ly tay. */
public record DeliveryAssignmentFailed(String assignmentId, String orderId, String reason) {}
