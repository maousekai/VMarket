package com.vmarket.delivery.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AssignmentRetryWorker {
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final DeliveryService deliveries;
    private final Clock clock;

    private record Claim(String id, String token, int attempts) {}

    public void runBatch() {
        for (int processed = 0; processed < 20; processed++) {
            Claim claim = new TransactionTemplate(transactions).execute(ignored -> claim());
            if (claim == null) return;
            try {
                // Auth HTTP is outside the claim transaction. Assignment rechecks lease + status
                // under the row lock; a late worker cannot overwrite an admin or another worker.
                deliveries.retryAssignment(claim.id(), claim.token());
            } catch (RuntimeException failure) {
                log.warn(
                        "Assignment retry {} failed: {}",
                        claim.id(),
                        failure.getClass().getSimpleName());
            } finally {
                int seconds = Math.min(300, 5 << Math.min(claim.attempts() - 1, 6));
                jdbc.update(
                        """
                        update delivery_assignments set next_assignment_at=?,
                          assignment_claim_token=null, assignment_claim_until=null
                        where id=? and assignment_claim_token=?
                        """,
                        Timestamp.from(clock.instant().plusSeconds(seconds)),
                        claim.id(),
                        claim.token());
            }
        }
    }

    private Claim claim() {
        var rows =
                jdbc.queryForList(
                        """
                        select id, assignment_attempts from delivery_assignments
                        where status='WAITING_ASSIGNMENT' and next_assignment_at <= ?
                          and (assignment_claim_token is null or assignment_claim_until <= ?)
                        order by next_assignment_at, created_at, id limit 1 for update skip locked
                        """,
                        Timestamp.from(clock.instant()),
                        Timestamp.from(clock.instant()));
        if (rows.isEmpty()) return null;
        var row = rows.get(0);
        String id = (String) row.get("id"), token = UUID.randomUUID().toString();
        int attempts = Math.min(1000, ((Number) row.get("assignment_attempts")).intValue() + 1);
        jdbc.update(
                """
                update delivery_assignments set assignment_attempts=?, assignment_claim_token=?,
                  assignment_claim_until=? where id=?
                """,
                attempts,
                token,
                Timestamp.from(clock.instant().plusSeconds(30)),
                id);
        return new Claim(id, token, attempts);
    }
}
