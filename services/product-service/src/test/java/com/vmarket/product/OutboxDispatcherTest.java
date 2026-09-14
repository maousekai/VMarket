package com.vmarket.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.vmarket.events.EventsJson;
import com.vmarket.events.ProductDeleted;
import com.vmarket.events.config.EventBusProperties;
import com.vmarket.product.event.OutboxDispatcher;
import com.vmarket.product.model.OutboxEvent;
import com.vmarket.product.repository.OutboxEventRepository;

@ExtendWith(MockitoExtension.class)
class OutboxDispatcherTest {
	@Mock OutboxEventRepository repository;
	@Mock RabbitTemplate rabbitTemplate;
	private OutboxDispatcher dispatcher;
	private OutboxEvent event;

	@BeforeEach
	void setUp() {
		EventBusProperties properties = new EventBusProperties();
		properties.setExchange("events");
		dispatcher = new OutboxDispatcher(repository, rabbitTemplate, properties, new EventsJson());
		event = new OutboxEvent("ProductDeleted", new ProductDeleted("product-1", "shop-1"));
		when(repository.findTop50ByPublishedAtIsNullAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(any()))
				.thenReturn(List.of(event));
	}

	@Test
	void successfulPublishMarksOutboxRecordAsPublished() {
		dispatcher.publishPending();

		verify(rabbitTemplate).send(eq("events"), eq("ProductDeleted"), any(Message.class));
		assertThat(event.getPublishedAt()).isNotNull();
		verify(repository).save(event);
	}

	@Test
	void publisherFailureSchedulesRetryWithoutLosingEvent() {
		doThrow(new RuntimeException("Rabbit down"))
				.when(rabbitTemplate).send(eq("events"), eq("ProductDeleted"), any(Message.class));

		dispatcher.publishPending();

		assertThat(event.getPublishedAt()).isNull();
		assertThat(event.getAttempts()).isEqualTo(1);
		assertThat(event.getNextAttemptAt()).isAfter(event.getCreatedAt());
		assertThat(event.getLastError()).contains("Rabbit down");
	}
}
