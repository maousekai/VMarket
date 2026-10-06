package com.vmarket.payment.event;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
public interface OutboxRepository extends JpaRepository<OutboxEvent, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select e from OutboxEvent e order by e.createdAt, e.id")
    List<OutboxEvent> next(Pageable page);
}
