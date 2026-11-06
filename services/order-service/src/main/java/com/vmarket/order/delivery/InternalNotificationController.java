package com.vmarket.order.delivery;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

/**
 * API đọc nội bộ cho notification-service (PBL6-23): người mua, gian hàng và tổng tiền
 * của đơn để gửi thông báo. Chỉ đọc, không khoá dòng (khác {@code /internal/delivery}).
 * Cùng khoá {@code X-Internal-Api-Key}; gateway không định tuyến {@code /internal/**}.
 */
@RestController
@RequestMapping("/internal/notification/orders")
@RequiredArgsConstructor
public class InternalNotificationController {
    private final OrderDeliveryBridge bridge;

    @GetMapping("/{id}")
    public JsonNode get(
            @PathVariable String id,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String key) {
        bridge.authorize(key);
        return bridge.view(id);
    }
}
