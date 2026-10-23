package com.vmarket.order.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.vmarket.order.entity.Order;

public interface OrderRepository extends JpaRepository<Order, String> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select o from Order o where o.id = :id")
    Optional<Order> lock(@org.springframework.data.repository.query.Param("id") String id);
    List<Order> findByStatusAndPaymentExpiresAtLessThanEqual(com.vmarket.order.entity.OrderStatus status, java.time.Instant time, org.springframework.data.domain.Pageable page);


	/** "Đơn hàng của tôi" — mới nhất trước (FR-ORDER-02). */
	List<Order> findByUserIdOrderByCreatedAtDesc(String userId);

	/**
	 * Luôn tra cứu kèm {@code userId} thay vì {@code findById} thuần: người dùng A
	 * đoán được id đơn của B cũng không đọc/huỷ được (IDOR — FR-ORDER-02).
	 */
	Optional<Order> findByIdAndUserId(String id, String userId);

	/**
	 * Tra cứu đơn đã tạo bởi idempotency key: nếu tồn tại → trả lại đơn cũ
	 * thay vì tạo đơn mới (chống retry / gửi đồng thời — P1 review PR #25).
	 */
	Optional<Order> findByUserIdAndIdempotencyKey(String userId, String idempotencyKey);
}
