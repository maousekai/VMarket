package com.vmarket.events;

import java.util.UUID;

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
	}

	@Override
	public void publish(String eventType, Object payload) {
		String eventId = UUID.randomUUID().toString();
		EventEnvelope envelope = new EventEnvelope(eventId, eventType, System.currentTimeMillis(), payload);

		MessageProperties messageProperties = new MessageProperties();
		messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		messageProperties.setMessageId(eventId);
		messageProperties.setType(eventType);

		rabbitTemplate.send(properties.getExchange(), eventType, new Message(json.write(envelope), messageProperties));
	}
}