package com.vmarket.order;

import com.vmarket.order.entity.Order;
import com.vmarket.order.entity.OrderStatus;

import org.springframework.test.util.ReflectionTestUtils;

/** Dựng fixture ở trạng thái tuỳ ý, bỏ qua bảng chuyển trạng thái (chỉ dùng trong test). */
final class TestOrders {
    private TestOrders() {}

    static void forceStatus(Order order, OrderStatus status) {
        ReflectionTestUtils.setField(order, "status", status);
    }
}
