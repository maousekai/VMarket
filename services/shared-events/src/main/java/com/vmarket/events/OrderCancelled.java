package com.vmarket.events;

/** Order Service phát để giải phóng tồn kho khi đơn bị hủy. */
public record OrderCancelled(String orderId) {
}
