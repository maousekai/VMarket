package com.vmarket.payment.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.vmarket.payment.entity.PendingReturnRefund;

public interface PendingReturnRefundRepository extends JpaRepository<PendingReturnRefund, String> {

    Optional<PendingReturnRefund> findByReturnId(String returnId);

    List<PendingReturnRefund> findByOrderIdAndProcessedFalseOrderByCreatedAtAsc(String orderId);
}

