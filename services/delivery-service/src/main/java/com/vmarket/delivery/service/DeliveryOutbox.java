package com.vmarket.delivery.service;

import com.vmarket.delivery.tracking.RedisTracking;
import com.vmarket.events.EventPublisher;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

@Component @RequiredArgsConstructor @Slf4j
@ConditionalOnProperty(name="app.outbox-enabled", havingValue="true", matchIfMissing=true)
public class DeliveryOutbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final EventPublisher publisher;
    private final RedisTracking tracking;
    private final Clock clock;
    // Separate flush methods keep a slow/down RabbitMQ from delaying GPS updates.
    @Scheduled(fixedDelayString="${app.outbox-delay:500}")
    public void tracking() { flush(true); }
    @Scheduled(fixedDelayString="${app.outbox-delay:500}")
    public void events() { flush(false); }
    private void flush(boolean gps) {
        String comparison = gps ? "=" : "<>";
        for (var row : jdbc.queryForList("select * from delivery_outbox where event_type " + comparison
                + " 'TrackingUpdate' and next_attempt_at <= ? order by next_attempt_at, created_at, id limit 100",
                Timestamp.from(clock.instant()))) {
            try {
                String body = (String)row.get("payload");
                if (gps) tracking.publish(body);
                else publisher.publish((String)row.get("event_type"), json.readTree(body));
                jdbc.update("delete from delivery_outbox where id=?", row.get("id"));
            } catch (Exception ex) {
                int attempts = ((Number)row.get("retry_attempts")).intValue();
                long delay = Math.min(300, 5L << Math.min(attempts, 6));
                var retryAt = clock.instant().plusSeconds(delay);
                jdbc.update("update delivery_outbox set retry_attempts=retry_attempts+1, next_attempt_at=? where id=?",
                        Timestamp.from(retryAt), row.get("id"));
                log.warn("Delivery {} outbox retry {} scheduled at {}: {}", gps ? "tracking" : "event",
                        attempts+1, retryAt, ex.getClass().getSimpleName());
                // Deferred rows leave the next batch free for later status/COD events,
                // even when a whole batch has no Notification route. Redis failures
                // affect the whole GPS channel, so stop this batch after scheduling retry.
                if (gps) break;
            }
        }
    }
}
