package com.vmarket.delivery.domain;
public enum DeliveryStatus {
    WAITING_HANDOFF, WAITING_ASSIGNMENT, ASSIGNED, PICKED_UP, IN_TRANSIT, FAILED, RETURNING, RETURNED, DELIVERED;
    public boolean tracking() { return this == PICKED_UP || this == IN_TRANSIT || this == RETURNING; }
    public boolean terminal() { return this == RETURNED || this == DELIVERED; }
}
