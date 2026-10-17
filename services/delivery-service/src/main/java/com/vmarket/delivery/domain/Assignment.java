package com.vmarket.delivery.domain;

import lombok.Data;

import java.time.Instant;

@Data
public class Assignment {
    private String id,
            orderId,
            buyerId,
            shipperId,
            paymentMethod,
            province,
            district,
            orderSnapshot;
    private DeliveryStatus status;
    private int attempt;
    private long codAmount, version, locationSequence, trackingSequence;
    private Double latitude, longitude;
    private Instant locationAt, createdAt, updatedAt;
}
