package com.vmarket.delivery.service;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Seller khong xac nhan "Da nhan lai hang" trong N ngay (delivery-rules.json) thi he thong tu
 * chuyen chuyen hoan sang RETURNED va ghi lich su (review PR 68, muc 4.5).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.return-auto-close.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ReturnAutoCloseJob {
    private final DeliveryService deliveries;

    @Scheduled(fixedDelayString = "${app.return-auto-close.delay:600000}")
    public void run() {
        deliveries.autoCloseReturns();
    }
}
