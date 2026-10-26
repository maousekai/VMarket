package com.vmarket.order.event;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OutboxRepository extends JpaRepository<OutboxEvent, String> {
    @Query(
            value =
                    """
select e.* from event_outbox e
where e.state='PENDING' and e.next_attempt_at <= :now
  and not exists (
    select 1 from event_outbox earlier where earlier.aggregate_id=e.aggregate_id
      and (earlier.created_at < e.created_at
        or (earlier.created_at=e.created_at and earlier.event_sequence < e.event_sequence)))
order by e.created_at, e.event_sequence limit 1 for update skip locked
""",
            nativeQuery = true)
    List<OutboxEvent> next(@Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from OutboxEvent e where e.id = :id")
    Optional<OutboxEvent> lock(@Param("id") String id);

    List<OutboxEvent> findByStateOrderByCreatedAtAscIdAsc(String state, Pageable page);

    long countByState(String state);

    @Query(
            value =
                    """
select count(*) from event_outbox e where e.state='PENDING' and exists (
  select 1 from event_outbox earlier where earlier.aggregate_id=e.aggregate_id
    and earlier.state='DEAD'
    and (earlier.created_at < e.created_at
      or (earlier.created_at=e.created_at and earlier.event_sequence < e.event_sequence)))
""",
            nativeQuery = true)
    long countBlockedByDead();
}
