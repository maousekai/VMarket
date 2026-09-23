package com.vmarket.order.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.vmarket.order.entity.Order;

public interface OrderRepository extends JpaRepository<Order, String> {

	/** "Đơn hàng của tôi" — mới nhất trước (FR-ORDER-02). */
	List<Order> findByUserIdOrderByCreatedAtDesc(String userId);

	/**
	 * Luôn tra cứu kèm {@code userId} thay vì {@code findById} thuần: người dùng A
	 * đoán được id đơn của B cũng không đọc/huỷ được (IDOR — FR-ORDER-02).
	 */
	Optional<Order> findByIdAndUserId(String id, String userId);
}