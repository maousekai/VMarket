package com.vmarket.order.event;

import com.vmarket.order.exception.ApiException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxRecovery {
    private final OutboxRepository events;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    // Payloads can contain personal data. The operations API exposes only recovery metadata.
    public record DeadEvent(
            String id,
            String aggregateId,
            String eventType,
            int attempts,
            String lastError,
            Instant createdAt) {}

    public record Status(long dead, long blocked, long oldestDeadAgeSeconds) {}

    @Transactional(readOnly = true)
    public List<DeadEvent> dead(int page, int size) {
        return events
                .findByStateOrderByCreatedAtAscIdAsc("DEAD", PageRequest.of(page, size))
                .stream()
                .map(
                        e ->
                                new DeadEvent(
                                        e.getId(),
                                        e.getAggregateId(),
                                        e.getEventType(),
                                        e.getRetryAttempts(),
                                        e.getLastError(),
                                        e.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Status status() {
        var oldest = dead(0, 1);
        long age =
                oldest.isEmpty()
                        ? 0
                        : Math.max(
                                0,
                                clock.instant().getEpochSecond()
                                        - oldest.get(0).createdAt().getEpochSecond());
        return new Status(events.countByState("DEAD"), events.countBlockedByDead(), age);
    }

    @Transactional
    public void replay(String id, String admin, String reason) {
        var event =
                events.lock(id)
                        .orElseThrow(
                                () ->
                                        ApiException.notFound(
                                                "OUTBOX_EVENT_NOT_FOUND",
                                                "Không tìm thấy sự kiện"));
        if (!"DEAD".equals(event.getState()))
            throw ApiException.conflict(
                    "OUTBOX_EVENT_NOT_DEAD",
                    "Chỉ khôi phục sự kiện DEAD sau khi sửa nguyên nhân lỗi");
        jdbc.update(
                """
                insert into outbox_replay_audit
                (id, event_id, aggregate_id, event_type, previous_attempts, previous_error,
                 replayed_by, reason, replayed_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                id,
                event.getAggregateId(),
                event.getEventType(),
                event.getRetryAttempts(),
                event.getLastError(),
                admin,
                reason.trim(),
                Timestamp.from(clock.instant()));
        event.setState("PENDING");
        event.setRetryAttempts(0);
        event.setLastError(null);
        event.setNextAttemptAt(clock.instant());
        // Preserve id, payload, created_at and sequence so successors remain blocked until ACK.
        log.warn(
                "Admin {} replayed order outbox event {} for aggregate {}",
                admin,
                id,
                event.getAggregateId());
    }
}
