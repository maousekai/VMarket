package com.vmarket.product;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;

import com.vmarket.events.EventsJson;
import com.vmarket.events.ProductDeleted;
import com.vmarket.events.config.EventBusProperties;
import com.vmarket.product.event.OutboxDispatcher;
import com.vmarket.product.model.OutboxEvent;
import com.vmarket.product.repository.OutboxEventStore;

@ExtendWith(MockitoExtension.class)
class OutboxDispatcherTest {
	@Mock OutboxEventStore store;
	@Mock RabbitTemplate rabbitTemplate;
	private OutboxDispatcher dispatcher;
	private OutboxEvent event;

	@BeforeEach
	void setUp() {
		EventBusProperties properties = new EventBusProperties();
		properties.setExchange("events");
		dispatcher = new OutboxDispatcher(store, rabbitTemplate, properties, new EventsJson());
		event = new OutboxEvent("ProductDeleted", new ProductDeleted("product-1", "shop-1"));
		when(store.claimNext(anyString(), any(Instant.class), any(Instant.class)))
				.thenReturn(Optional.of(event), Optional.empty());
	}

	@Test
	void successfulPublishMarksOutboxRecordAsPublished() {
		doAnswer(invocation -> {
			CorrelationData correlation = invocation.getArgument(3);
			correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
			return null;
		}).when(rabbitTemplate).send(eq("events"), eq("ProductDeleted"), any(Message.class), any(CorrelationData.class));

		dispatcher.publishPending();

		verify(rabbitTemplate).send(eq("events"), eq("ProductDeleted"), any(Message.class), any(CorrelationData.class));
		verify(store).markPublished(eq(event.getId()), anyString(), any(Instant.class));
	}

	@Test
	void publisherFailureSchedulesRetryWithoutLosingEvent() {
		doThrow(new RuntimeException("Rabbit down"))
				.when(rabbitTemplate).send(eq("events"), eq("ProductDeleted"), any(Message.class), any(CorrelationData.class));

		dispatcher.publishPending();

		verify(store).markFailed(eq(event.getId()), anyString(), any(Instant.class), eq("Rabbit down"));
	}
}
