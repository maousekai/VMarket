package com.vmarket.events.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.TopicExchange;

class EventBusAutoConfigurationTest {

	private final EventBusAutoConfiguration configuration = new EventBusAutoConfiguration();

	@Test
	void eventDeclarables_throwsWhenListenTrueAndQueueBlank() {
		EventBusProperties properties = new EventBusProperties();
		properties.setListen(true);
		properties.setQueue("");

		TopicExchange exchange = new TopicExchange("vmarket.events");

		assertThatThrownBy(() -> configuration.eventDeclarables(properties, exchange))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("app.events.queue không được để trống khi app.events.listen=true");
	}

	@Test
	void eventDeclarables_createsQueueAndBindingsWhenQueueConfigured() {
		EventBusProperties properties = new EventBusProperties();
		properties.setListen(true);
		properties.setQueue("test.events");
		properties.setBindings(List.of("ProductCreated", "OrderPlaced"));

		TopicExchange exchange = new TopicExchange("vmarket.events");

		Declarables declarables = configuration.eventDeclarables(properties, exchange);
		assertThat(declarables.getDeclarables()).hasSize(3); // 1 Queue + 2 Bindings
	}
}
