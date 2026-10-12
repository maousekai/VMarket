package com.vmarket.delivery.domain;
import java.time.Instant;
import lombok.Data;
@Data
public class Assignment {
    private String id, orderId, buyerId, shipperId, paymentMethod, province, district, orderSnapshot;
    private DeliveryStatus status;
    private int attempt;
    private long codAmount, version;
    private Double latitude, longitude;
    private Instant locationAt, createdAt, updatedAt;
}
