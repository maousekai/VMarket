package com.vmarket.delivery.domain;

import java.time.Instant;

import lombok.Data;

@Data
public class Assignment {
    private String id,
            orderId,
            buyerId,
            shipperId,
            paymentMethod,
            province,
            district,
            ward,
            orderSnapshot;
    private DeliveryStatus status;
    private int attempt;
    private long codAmount, version, locationSequence, trackingSequence;
    private Double latitude, longitude;
    private Instant locationAt, createdAt, updatedAt;
    /** Ma ly do that bai gan nhat (DeliveryRules.FailureReason). */
    private String failureReasonCode;
    /** Lan that bai gan nhat: giao lai phai cach it nhat retryHours. */
    private Instant lastFailedAt;
    /** Shipper bi khoa khi dang giu hang: admin phai xu ly. */
    private boolean needsAttention;
    /** Nguon GPS dang duoc chap nhan (MOBILE / WEB) va lan gui gan nhat cua no. */
    private String gpsSource;
    private Instant gpsSourceAt;
    /** Luc shipper tra hang xong cho moi shop (RETURN_DROPPED_OFF). */
    private Instant droppedOffAt;
}
