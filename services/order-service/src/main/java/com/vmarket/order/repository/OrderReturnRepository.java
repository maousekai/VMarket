package com.vmarket.order.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.vmarket.order.entity.OrderReturn;
import com.vmarket.order.entity.ReturnStatus;

import jakarta.persistence.LockModeType;

public interface OrderReturnRepository extends JpaRepository<OrderReturn, String> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from OrderReturn r where r.id = :id")
	Optional<OrderReturn> lock(@Param("id") String id);

	List<OrderReturn> findByOrderId(String orderId);

	List<OrderReturn> findByUserIdOrderByCreatedAtDesc(String userId);

	Optional<OrderReturn> findByIdAndUserId(String id, String userId);

	Optional<OrderReturn> findByIdAndShopId(String id, String shopId);

	Page<OrderReturn> findByShopIdOrderByCreatedAtDesc(String shopId, Pageable pageable);

	Page<OrderReturn> findByShopIdAndStatusOrderByCreatedAtDesc(String shopId, ReturnStatus status, Pageable pageable);

	Page<OrderReturn> findAllByOrderByCreatedAtDesc(Pageable pageable);

	Page<OrderReturn> findByStatusOrderByCreatedAtDesc(ReturnStatus status, Pageable pageable);

	boolean existsByOrderIdAndStatusIn(String orderId, List<ReturnStatus> statuses);

	List<OrderReturn> findByStatusAndCreatedAtLessThanEqual(ReturnStatus status, java.time.Instant cutoff);

	@Query("select count(i) > 0 from OrderReturnItem i where i.orderReturn.orderId = :orderId and i.orderItemId = :orderItemId and i.orderReturn.status <> 'CANCELLED'")
	boolean isOrderItemAlreadyRequested(@Param("orderId") String orderId, @Param("orderItemId") String orderItemId);
}
