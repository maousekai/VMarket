package com.vmarket.events;

/** Product Catalog phát khi không thể giữ đủ tồn kho cho đơn mới. */
public record StockReservationFailed(String orderId, String code, String message) {
}
