package com.vmarket.order.stats;

import java.math.BigDecimal;
import java.time.Instant;

import com.vmarket.order.entity.OrderStatus;

/**
 * Một dòng item thuộc gian hàng trong khoảng thời gian thống kê.
 *
 * <p>Một đơn có nhiều item nên cùng {@code orderId} xuất hiện nhiều lần; service
 * tự distinct khi đếm đơn và cộng doanh thu theo từng dòng.
 */
public interface ShopOrderRow {

	String getOrderId();

	OrderStatus getStatus();

	Instant getCreatedAt();

	String getProductId();

	int getQuantity();

	BigDecimal getLineTotal();
}
