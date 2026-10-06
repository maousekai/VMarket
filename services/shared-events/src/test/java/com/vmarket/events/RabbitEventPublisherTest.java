package com.vmarket.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import java.util.concurrent.CompletableFuture;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.vmarket.events.config.EventBusProperties;

class RabbitEventPublisherTest {
	private RabbitEventPublisher confirmed() {
		properties.setConfirmedPublication(true);
		properties.setConfirmTimeoutMillis(50);
		return new RabbitEventPublisher(rabbitTemplate, properties, json);
	}

	private void respond(boolean ack, boolean returned) {
		doAnswer(invocation -> {
			CorrelationData data = invocation.getArgument(3);
			Message message = invocation.getArgument(2);
			CompletableFuture.runAsync(() -> {
				if (returned) data.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE", "vmarket.events", "test"));
				data.getFuture().complete(new CorrelationData.Confirm(ack, ack ? null : "nack"));
			});
			return null;
		}).when(rabbitTemplate).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
	}

	@Test void confirmedPublicationWaitsForAsynchronousAck() {
		var confirmed = confirmed(); respond(true, false);
		confirmed.publish("test", java.util.Map.of("id", "1"));
		verify(rabbitTemplate).setMandatory(true);
	}
	@Test void asynchronousNackCannotCompletePublication() {
		var confirmed = confirmed(); respond(false, false);
		assertThatThrownBy(() -> confirmed.publish("test", java.util.Map.of())).isInstanceOf(AmqpException.class);
	}
	@Test void returnedMessageCannotCompletePublicationEvenWithAck() {
		var confirmed = confirmed(); respond(true, true);
		assertThatThrownBy(() -> confirmed.publish("test", java.util.Map.of())).isInstanceOf(AmqpException.class);
	}
	@Test void missingConfirmationTimesOut() {
		var confirmed = confirmed();
		assertThatThrownBy(() -> confirmed.publish("test", java.util.Map.of())).isInstanceOf(AmqpException.class);
	}

	private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
	private final EventBusProperties properties = new EventBusProperties();
	private final EventsJson json = new EventsJson();
	private final RabbitEventPublisher publisher = new RabbitEventPublisher(rabbitTemplate, properties, json);

	@Test
	void publish_sendsEnvelopeToExchangeWithRoutingKeyAsType() {
		ProductCreated payload = new ProductCreated("p1", "s1", "Áo thun", 100000L,
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
