package com.vmarket.delivery.service;

import com.vmarket.delivery.tracking.RedisTracking;
import com.vmarket.events.EventPublisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.outbox-enabled", havingValue = "true", matchIfMissing = true)
public class DeliveryOutbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final EventPublisher publisher;
    private final RedisTracking tracking;
    private final Clock clock;
    private final PlatformTransactionManager transactions;
    public static final int MAX_ATTEMPTS = 10;

    // Separate flush methods keep a slow/down RabbitMQ from delaying GPS updates.
    @Scheduled(fixedDelayString = "${app.outbox-delay:500}")
    public void tracking() {
        flush(true);
    }

    @Scheduled(fixedDelayString = "${app.outbox-delay:500}")
    public void events() {
        flush(false);
    }

    private void flush(boolean gps) {
        for (int processed = 0; processed < 100; processed++) {
            boolean more =
                    Boolean.TRUE.equals(
                            new TransactionTemplate(transactions)
                                    .execute(ignored -> flushOne(gps)));
            if (!more) break;
        }
    }

    private boolean flushOne(boolean gps) {
        String channel = gps ? "in" : "not in";
        var rows =
                jdbc.queryForList(
                        "select * from delivery_outbox where event_type "
                                + channel
                                + " ('TrackingUpdate', 'TrackingAccessRevoked') and state='PENDING'"
                                + " and next_attempt_at <= ? order by next_attempt_at, created_at,"
                                + " id limit 1 for update skip locked",
                        Timestamp.from(clock.instant()));
        if (rows.isEmpty()) return false;
        var row = rows.get(0);
        try {
            String body = (String) row.get("payload");
            if (gps) tracking.publish(body);
            else publisher.publish((String) row.get("event_type"), json.readTree(body));
            jdbc.update("delete from delivery_outbox where id=?", row.get("id"));
        } catch (Exception ex) {
            int attempts = ((Number) row.get("retry_attempts")).intValue() + 1;
            long delay = Math.min(300, 5L << Math.min(attempts - 1, 6));
            var retryAt = clock.instant().plusSeconds(delay);
            String state = attempts >= MAX_ATTEMPTS ? "DEAD" : "PENDING";
            jdbc.update(
                    "update delivery_outbox set retry_attempts=?, next_attempt_at=?, state=?,"
                            + " last_error=? where id=?",
                    attempts,
                    Timestamp.from(retryAt),
                    state,
                    ex.getClass().getSimpleName(),
                    row.get("id"));
            log.warn(
                    "Delivery {} outbox {} after {} attempts",
                    gps ? "tracking" : "event",
                    state,
                    attempts);
            // Deferred rows leave the next batch free for later status/COD events,
            // even when a whole batch has no Notification route. Redis failures
            // affect the whole GPS channel, so stop this batch after scheduling retry.
            if (gps) return false;
        }
        return true;
    }
}
