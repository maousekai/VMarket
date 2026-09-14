package com.vmarket.events;

/** Order Service phát để chốt tồn kho cho đơn COD đã được Seller xác nhận. */
public record OrderConfirmed(String orderId) {
}
