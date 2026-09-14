package com.vmarket.product.event;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventsJson;
import com.vmarket.events.config.EventBusProperties;
import com.vmarket.product.model.OutboxEvent;
import com.vmarket.product.repository.OutboxEventRepository;

@Component
@ConditionalOnProperty(prefix = "app.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxDispatcher {
	private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
	private final OutboxEventRepository repository;
	private final RabbitTemplate rabbitTemplate;
	private final EventBusProperties properties;
	private final EventsJson json;

	public OutboxDispatcher(OutboxEventRepository repository, RabbitTemplate rabbitTemplate,
			EventBusProperties properties, EventsJson json) {
		this.repository = repository;
		this.rabbitTemplate = rabbitTemplate;
		this.properties = properties;
		this.json = json;
	}

	@Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}",
			initialDelayString = "${app.outbox.initial-delay-ms:1000}")
	public void publishPending() {
		for (OutboxEvent event : repository
				.findTop50ByPublishedAtIsNullAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(Instant.now())) {
			try {
				EventEnvelope envelope = new EventEnvelope(event.getId(), event.getEventType(),
						event.getCreatedAt().toEpochMilli(), event.getPayload());
				MessageProperties messageProperties = new MessageProperties();
				messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
				messageProperties.setMessageId(event.getId());
				messageProperties.setType(event.getEventType());
				rabbitTemplate.send(properties.getExchange(), event.getEventType(),
						new Message(json.write(envelope), messageProperties));
				event.setPublishedAt(Instant.now());
				event.setLastError(null);
			} catch (RuntimeException ex) {
				event.setAttempts(event.getAttempts() + 1);
				long delaySeconds = Math.min(60, 1L << Math.min(event.getAttempts(), 6));
				event.setNextAttemptAt(Instant.now().plus(delaySeconds, ChronoUnit.SECONDS));
				event.setLastError(abbreviate(ex.getMessage()));
				log.warn("Không thể publish outbox event {} (lần {}): {}", event.getId(),
						event.getAttempts(), ex.getMessage());
			}
			repository.save(event);
		}
	}

	private String abbreviate(String message) {
		if (message == null) return "Unknown publisher error";
		return message.length() <= 500 ? message : message.substring(0, 500);
	}
}
