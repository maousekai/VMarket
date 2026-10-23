package com.vmarket.payment.repository;

import com.vmarket.payment.entity.PaymentTransaction;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.*;

public interface PaymentRepository extends JpaRepository<PaymentTransaction, Long> {
    Optional<PaymentTransaction> findByOrderId(String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentTransaction p where p.orderId = :id")
    Optional<PaymentTransaction> lockByOrderId(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentTransaction p where p.id = :id")
    Optional<PaymentTransaction> lock(@Param("id") Long id);

    List<PaymentTransaction> findByBuyerIdOrderByCreatedAtDesc(String buyerId, Pageable page);

    List<PaymentTransaction> findByStatusInAndExpiresAtLessThanEqual(
            List<String> statuses, Instant time, Pageable page);

    List<PaymentTransaction>
            findByStatusInAndSettlementExpiresAtLessThanEqualOrderBySettlementExpiresAtAscIdAsc(
                    List<String> statuses, Instant time, Pageable page);

    List<PaymentTransaction>
            findByCancelPendingTrueAndNextCancelAttemptAtLessThanEqualOrderByNextCancelAttemptAtAscIdAsc(
                    Instant time, Pageable page);

    List<PaymentTransaction> findByReconciliationRequiredTrueOrderByCreatedAtAsc(Pageable page);
}
