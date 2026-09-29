package com.vmarket.order.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.vmarket.order.entity.OrderItem;

public interface OrderItemRepository extends JpaRepository<OrderItem, String> {

	/** Nạp item của một đơn theo đúng thứ tự thêm vào (ULID tăng theo thời gian). */
	List<OrderItem> findByOrderIdOrderByIdAsc(String orderId);
}