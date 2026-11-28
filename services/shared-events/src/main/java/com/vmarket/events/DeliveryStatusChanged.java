package com.vmarket.events;

import java.time.Instant;

/**
 * version is monotonic per order; consumers must ignore older deliveries.
 *
 * @param publicReason nhan cong khai cua ly do that bai (chi co khi status = FAILED/RETURNING)
 * @param earliestRetryAt gio som nhat duoc giao lai sau mot lan that bai (null neu khong giao lai)
 */
public record DeliveryStatusChanged(
        String orderId, String status, long version, String publicReason, Instant earliestRetryAt) {

    public DeliveryStatusChanged(String orderId, String status, long version) {
        this(orderId, status, version, null, null);
    }
}
