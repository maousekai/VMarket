package com.vmarket.order.delivery;
import com.vmarket.events.EventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="app.delivery-outbox-enabled", havingValue="true", matchIfMissing=true)
@Component
@RequiredArgsConstructor
@Slf4j
public class DeliveryOrderOutbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final EventPublisher publisher;
    @Scheduled(fixedDelayString="${app.delivery-outbox-delay:1000}")
    public void flush() {
        for (var row : jdbc.queryForList("select * from delivery_order_outbox order by created_at, id limit 50")) {
            try {
                publisher.publish((String) row.get("event_type"), json.readTree((String) row.get("payload")));
                jdbc.update("delete from delivery_order_outbox where id = ?", row.get("id"));
            } catch (Exception ex) {
                log.warn("Delivery order event pending: {}", ex.getClass().getSimpleName());
                break;
            }
        }
    }
}
