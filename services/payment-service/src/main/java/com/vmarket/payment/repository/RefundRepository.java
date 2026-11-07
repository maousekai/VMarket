package com.vmarket.payment.repository;

import com.vmarket.payment.entity.RefundRequest;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.*;

public interface RefundRepository extends JpaRepository<RefundRequest, String> {

    Optional<RefundRequest> findFirstByPaymentIdOrderByRequestedAtDesc(Long id);

    Optional<RefundRequest> findFirstByPaymentIdAndReturnIdIsNull(Long id);

    default Optional<RefundRequest> findByPaymentId(Long id) {
        return findFirstByPaymentIdOrderByRequestedAtDesc(id);
    }

    Optional<RefundRequest> findByReturnId(String returnId);

    List<RefundRequest> findAllByPaymentId(Long paymentId);

    List<RefundRequest> findByStatusOrderByRequestedAtAsc(String status, Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefundRequest r where r.id = :id")
    Optional<RefundRequest> lock(@Param("id") String id);
}
