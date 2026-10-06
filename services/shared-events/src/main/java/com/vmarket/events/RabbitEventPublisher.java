package com.vmarket.events;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.vmarket.events.config.EventBusProperties;

/**
 * Hiện thực mặc định của {@link EventPublisher} dùng RabbitTemplate.
 *
 * <p>Luồng: bọc payload vào {@link EventEnvelope} (có {@code eventId},
 * {@code eventType}, {@code timestamp}), serialize JSON rồi gửi raw bytes lên
 * topic exchange với routing key = {@code eventType}. Dùng {@code send(...)}
 * thay vì {@code convertAndSend(...)} để tự làm chủ JSON (Jackson 3), không phụ
 * thuộc MessageConverter của Spring AMQP.
 */
public class RabbitEventPublisher implements EventPublisher {

	private final RabbitTemplate rabbitTemplate;
	private final EventBusProperties properties;
	private final EventsJson json;

	public RabbitEventPublisher(RabbitTemplate rabbitTemplate, EventBusProperties properties, EventsJson json) {
		this.rabbitTemplate = rabbitTemplate;
		this.properties = properties;
		this.json = json;
		if (properties.isConfirmedPublication()) rabbitTemplate.setMandatory(true);
	}

	@Override
	public void publish(String eventType, Object payload) {
		String eventId = UUID.randomUUID().toString();
		EventEnvelope envelope = new EventEnvelope(eventId, eventType, System.currentTimeMillis(), payload);

		MessageProperties messageProperties = new MessageProperties();
		messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		messageProperties.setMessageId(eventId);
		messageProperties.setType(eventType);

		Message message = new Message(json.write(envelope), messageProperties);
		if (!properties.isConfirmedPublication()) {
			rabbitTemplate.send(properties.getExchange(), eventType, message);
			return;
		}
		CorrelationData correlation = new CorrelationData(eventId);
		rabbitTemplate.send(properties.getExchange(), eventType, message, correlation);
		try {
			var confirm = correlation.getFuture().get(properties.getConfirmTimeoutMillis(), TimeUnit.MILLISECONDS);
			// Spring populates returned messages before completing the correlated ACK future.
			if (!confirm.isAck() || correlation.getReturned() != null)
				throw new AmqpException("Event publication was rejected or unroutable");
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AmqpException("Interrupted while waiting for event confirmation", ex);
		} catch (ExecutionException | TimeoutException ex) {
			throw new AmqpException("Event confirmation unavailable", ex);
		}
	}
}
