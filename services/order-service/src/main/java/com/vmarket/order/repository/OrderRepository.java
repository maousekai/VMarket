package com.vmarket.order.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.vmarket.order.entity.Order;
import com.vmarket.order.entity.OrderStatus;

import jakarta.persistence.LockModeType;

public interface OrderRepository extends JpaRepository<Order, String> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select o from Order o where o.id = :id")
	Optional<Order> lock(@Param("id") String id);

	List<Order> findByStatusAndPaymentExpiresAtLessThanEqual(OrderStatus status, Instant time, Pageable page);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select o from Order o where o.id = :id and o.userId = :userId")
	Optional<Order> findOwnForDeliveryUpdate(@Param("id") String id, @Param("userId") String userId);

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

	// --- [Seller] FR-ORD-04 ---
	@Query("select distinct o from Order o join o.items i where i.shopId = :shopId order by o.createdAt desc")
	Page<Order> findByShopId(@Param("shopId") String shopId, Pageable pageable);

	@Query("select distinct o from Order o join o.items i where i.shopId = :shopId and o.status = :status order by o.createdAt desc")
	Page<Order> findByShopIdAndStatus(@Param("shopId") String shopId, @Param("status") OrderStatus status, Pageable pageable);

	@Query("select distinct o from Order o join o.items i where o.id = :id and i.shopId = :shopId")
	Optional<Order> findByIdAndShopId(@Param("id") String id, @Param("shopId") String shopId);

	// --- [Admin] FR-ORD-05 ---
	Page<Order> findAllByOrderByCreatedAtDesc(Pageable pageable);

	Page<Order> findByStatusOrderByCreatedAtDesc(OrderStatus status, Pageable pageable);

	Page<Order> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

	Page<Order> findByUserIdAndStatusOrderByCreatedAtDesc(String userId, OrderStatus status, Pageable pageable);

	// --- [Tự động hoàn tất đơn] FR-ORD-02 ---
	List<Order> findByStatusAndUpdatedAtLessThanEqual(OrderStatus status, Instant cutoff);

	List<Order> findByStatusAndDeliveredAtLessThanEqual(OrderStatus status, Instant cutoff);
}
