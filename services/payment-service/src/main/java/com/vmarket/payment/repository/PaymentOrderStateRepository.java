package com.vmarket.payment.repository;
import com.vmarket.payment.entity.PaymentOrderState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
public interface PaymentOrderStateRepository extends JpaRepository<PaymentOrderState, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from PaymentOrderState s where s.orderId = :id")
    Optional<PaymentOrderState> lock(@Param("id") String id);
}
