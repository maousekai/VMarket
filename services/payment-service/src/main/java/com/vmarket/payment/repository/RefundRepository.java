package com.vmarket.payment.repository;
import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import com.vmarket.payment.entity.RefundRequest;
public interface RefundRepository extends JpaRepository<RefundRequest, String> {
    Optional<RefundRequest> findByPaymentId(Long id);
    List<RefundRequest> findByStatusOrderByRequestedAtAsc(String status, Pageable page);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from RefundRequest r where r.id = :id")
    Optional<RefundRequest> lock(@Param("id") String id);
}
