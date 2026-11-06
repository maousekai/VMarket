package com.vmarket.order.delivery;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import com.vmarket.order.entity.Order;
import com.vmarket.order.entity.OrderItem;

/**
 * Dữ liệu tối thiểu notification-service (PBL6-23) cần để gửi thông báo: người mua, gian
 * hàng, trạng thái và tổng tiền. Cố ý KHÔNG có tên / số điện thoại / địa chỉ / ghi chú
 * người nhận (khác snapshot của Delivery).
 */
public record OrderNotificationView(
        String id,
        String userId,
        String status,
        BigDecimal totalAmount,
        String paymentMethod,
        List<String> shopIds) {

    public static OrderNotificationView from(Order order) {
        return new OrderNotificationView(
                order.getId(),
                order.getUserId(),
                order.getStatus().name(),
                order.getTotalAmount(),
                order.getPaymentMethod(),
                order.getItems().stream()
                        .map(OrderItem::getShopId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList());
    }
}
