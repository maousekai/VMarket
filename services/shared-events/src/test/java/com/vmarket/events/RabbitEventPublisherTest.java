package com.vmarket.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.vmarket.events.config.EventBusProperties;

class RabbitEventPublisherTest {

	private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
	private final EventBusProperties properties = new EventBusProperties();
	private final EventsJson json = new EventsJson();
	private final RabbitEventPublisher publisher = new RabbitEventPublisher(rabbitTemplate, properties, json);

	@Test
	void publish_sendsEnvelopeToExchangeWithRoutingKeyAsType() {
		ProductCreated payload = new ProductCreated("p1", "s1", "Áo thun", new BigDecimal("100000"),
				"ACTIVE", List.of("https://cdn/1.jpg"));

		publisher.publish(EventType.PRODUCT_CREATED, payload);

		ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
		verify(rabbitTemplate).send(eq("vmarket.events"), eq("ProductCreated"), messageCaptor.capture());

		Message message = messageCaptor.getValue();
		MessageProperties props = message.getMessageProperties();
		assertThat(props.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
		assertThat(props.getType()).isEqualTo("ProductCreated");
		assertThat(props.getMessageId()).isNotBlank();

		EventEnvelope envelope = json.readEnvelope(message.getBody());
		assertThat(envelope.eventType()).isEqualTo("ProductCreated");
		ProductCreated roundTrip = json.convert(envelope.payload(), ProductCreated.class);
		assertThat(roundTrip.productId()).isEqualTo("p1");
		assertThat(roundTrip.shopId()).isEqualTo("s1");
	}
}