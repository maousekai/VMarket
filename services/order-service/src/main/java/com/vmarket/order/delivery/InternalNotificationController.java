package com.vmarket.order.delivery;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API đọc nội bộ cho notification-service (PBL6-23): chỉ trả {@link OrderNotificationView}
 * (id, người mua, trạng thái, tổng tiền, phương thức thanh toán, danh sách shopId) — không
 * có tên, số điện thoại, địa chỉ hay ghi chú người nhận. Chỉ đọc, không khoá dòng (khác
 * {@code /internal/delivery}).
 * Cùng khoá {@code X-Internal-Api-Key}; gateway không định tuyến {@code /internal/**}.
 */
@RestController
@RequestMapping("/internal/notification/orders")
@RequiredArgsConstructor
public class InternalNotificationController {
    private final OrderDeliveryBridge bridge;

    @GetMapping("/{id}")
    public OrderNotificationView get(
            @PathVariable String id,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String key) {
        bridge.authorize(key);
        return bridge.view(id);
    }
}
