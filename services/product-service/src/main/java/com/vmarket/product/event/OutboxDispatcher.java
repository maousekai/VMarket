package com.vmarket.product.event;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventsJson;
import com.vmarket.events.config.EventBusProperties;
import com.vmarket.product.model.OutboxEvent;
import com.vmarket.product.repository.OutboxEventStore;

@Component
@ConditionalOnProperty(prefix = "app.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxDispatcher {
	private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
	private final OutboxEventStore store;
	private final RabbitTemplate rabbitTemplate;
	private final EventBusProperties properties;
	private final EventsJson json;
	private final MeterRegistry metrics;
	private final String workerId = UUID.randomUUID().toString();
	private int batchSize = 50;
	private long leaseSeconds = 30;
	private long confirmTimeoutSeconds = 10;
	private int maxAttempts = 10;

	public OutboxDispatcher(OutboxEventStore store, RabbitTemplate rabbitTemplate,
			EventBusProperties properties, EventsJson json, MeterRegistry metrics) {
		this.store = store;
		this.rabbitTemplate = rabbitTemplate;
		this.properties = properties;
		this.json = json;
		this.metrics = metrics;
		this.rabbitTemplate.setMandatory(true);
		// Đăng ký gauge ĐÚNG MỘT LẦN: hàm được Micrometer đọc lại mỗi lần scrape,
		// nên giá trị luôn tươi mà không cần đăng ký lại (đăng ký lại gây WARN spam).
		this.metrics.gauge("vmarket.outbox.pending", store, s -> s.countPending(Instant.now()));
		this.metrics.gauge("vmarket.outbox.dead.total", store, OutboxEventStore::countDead);
	}

	@Value("${app.outbox.batch-size:50}")
	void setBatchSize(int batchSize) {
		this.batchSize = Math.max(1, batchSize);
	}

	@Value("${app.outbox.lease-seconds:30}")
	void setLeaseSeconds(long leaseSeconds) {
		this.leaseSeconds = Math.max(1, leaseSeconds);
	}

	@Value("${app.outbox.confirm-timeout-seconds:10}")
	void setConfirmTimeoutSeconds(long confirmTimeoutSeconds) {
		this.confirmTimeoutSeconds = Math.max(1, confirmTimeoutSeconds);
	}

	@Value("${app.outbox.max-attempts:10}")
	void setMaxAttempts(int maxAttempts) {
		this.maxAttempts = Math.max(1, maxAttempts);
	}

	@Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}",
			initialDelayString = "${app.outbox.initial-delay-ms:1000}")
	public void publishPending() {
		for (int processed = 0; processed < batchSize; processed++) {
			Instant claimedAt = Instant.now();
			OutboxEvent event = store.claimNext(workerId, claimedAt, claimedAt.plusSeconds(leaseSeconds))
					.orElse(null);
			if (event == null) return;
			try {
				publishAndAwaitBroker(event);
				if (!store.markPublished(event.getId(), workerId, Instant.now())) {
					log.warn("Mất lease trước khi đánh dấu outbox event {} đã publish", event.getId());
				}
			} catch (RuntimeException ex) {
				String reason = abbreviate(ex.getMessage());
				int nextAttempt = event.getAttempts() + 1;
				if (nextAttempt >= maxAttempts) {
					// Cạn lượt thử (vd: unroutable, broker NACK vĩnh viễn) — đánh dấu DEAD
					// để ngừng retry vô hạn; event vẫn nằm trong collection để re-drive
					// thủ công (xem docs/event-bus.md §7). Mikrometer tăng chỉ số outbox.dead.
					if (store.markDead(event.getId(), workerId, reason)) {
						log.error("Outbox event {} đã bị đánh dấu DEAD sau {} lần thử: {}", event.getId(),
								nextAttempt, reason);
						metrics.counter("vmarket.outbox.dead",
								"eventType", event.getEventType()).increment();
					}
				} else {
					long delaySeconds = Math.min(60, 1L << Math.min(nextAttempt, 6));
					store.markFailed(event.getId(), workerId,
							Instant.now().plus(delaySeconds, ChronoUnit.SECONDS), reason);
					log.warn("Không thể publish outbox event {} (lần {}): {}", event.getId(),
							nextAttempt, reason);
					metrics.counter("vmarket.outbox.retry",
							"eventType", event.getEventType()).increment();
				}
			}
		}
	}

	private void publishAndAwaitBroker(OutboxEvent event) {
		EventEnvelope envelope = new EventEnvelope(event.getId(), event.getEventType(),
				event.getCreatedAt().toEpochMilli(), event.getPayload());
		MessageProperties messageProperties = new MessageProperties();
		messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		messageProperties.setMessageId(event.getId());
		messageProperties.setType(event.getEventType());
		CorrelationData correlation = new CorrelationData(event.getId());
		rabbitTemplate.send(properties.getExchange(), event.getEventType(),
				new Message(json.write(envelope), messageProperties), correlation);
		try {
			CorrelationData.Confirm confirm = correlation.getFuture()
					.get(confirmTimeoutSeconds, TimeUnit.SECONDS);
			if (!confirm.ack()) {
				throw new IllegalStateException("Broker NACK: " + confirm.reason());
			}
			if (correlation.getReturned() != null) {
				throw new IllegalStateException("Event không được route tới queue: "
						+ correlation.getReturned().getReplyText());
			}
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Bị gián đoạn khi chờ broker confirm", ex);
		} catch (ExecutionException | TimeoutException ex) {
			throw new IllegalStateException("Không nhận được broker confirm", ex);
		}
	}

	private String abbreviate(String message) {
		if (message == null) return "Unknown publisher error";
		return message.length() <= 500 ? message : message.substring(0, 500);
	}
}
