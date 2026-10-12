package com.vmarket.delivery.service;

import com.vmarket.delivery.tracking.RedisTracking;
import com.vmarket.events.EventPublisher;
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
    // Separate flush methods keep a slow/down RabbitMQ from delaying GPS updates.
    @Scheduled(fixedDelayString="${app.outbox-delay:500}")
    public void tracking() { flush(true); }
    @Scheduled(fixedDelayString="${app.outbox-delay:500}")
    public void events() { flush(false); }
    private void flush(boolean gps) {
        String comparison = gps ? "=" : "<>";
        for (var row : jdbc.queryForList("select * from delivery_outbox where event_type " + comparison + " 'TrackingUpdate' order by created_at, id limit 100")) {
            try {
                String body = (String)row.get("payload");
                if (gps) tracking.publish(body);
                else publisher.publish((String)row.get("event_type"), json.readTree(body));
                jdbc.update("delete from delivery_outbox where id=?", row.get("id"));
            } catch (Exception ex) {
                log.warn("Delivery {} outbox pending: {}", gps ? "tracking" : "event", ex.getClass().getSimpleName());
                // A missing Notification binding for DeliveryAssigned must not block
                // routable status/COD events. Redis failures affect the whole GPS channel.
                if (gps) break;
            }
        }
    }
}
