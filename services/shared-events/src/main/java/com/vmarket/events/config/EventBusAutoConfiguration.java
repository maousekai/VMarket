package com.vmarket.events.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import com.vmarket.events.EventConsumer;
import com.vmarket.events.EventConsumerDispatcher;
import com.vmarket.events.EventConsumerRegistry;
import com.vmarket.events.EventPublisher;
import com.vmarket.events.EventsJson;
import com.vmarket.events.RabbitEventPublisher;

/**
 * Tự động cấu hình event bus khi có RabbitMQ trên classpath.
 *
 * <p>Bộ bean "luôn bật" phục vụ publish + đăng ký consumer:
 * <ul>
 *   <li>{@code EventsJson} — serialize/deserialize JSON (Jackson 3).</li>
 *   <li>{@code TopicExchange} — exchange chung {@code vmarket.events}.</li>
 *   <li>{@link RabbitEventPublisher} — hiện thực {@link EventPublisher}.</li>
 *   <li>{@link EventConsumerRegistry} + {@link EventConsumerDispatcher} — gom và
 *       định tuyến các {@link EventConsumer} bean.</li>
 * </ul>
 *
 * <p>Bộ bean "lắng nghe" chỉ kích hoạt khi {@code app.events.listen=true}: khai
 * báo queue + binding và mở listener container. Để tránh service chỉ-publish phải
 * giữ kết nối consumer lúc startup, mặc định {@code listen=false}.
 */
@AutoConfiguration
@EnableConfigurationProperties(EventBusProperties.class)
@ConditionalOnClass(RabbitTemplate.class)
public class EventBusAutoConfiguration {

	@Bean
	EventsJson eventsJson() {
		return new EventsJson();
	}

	@Bean
	TopicExchange eventExchange(EventBusProperties properties) {
		return new TopicExchange(properties.getExchange(), true, false);
	}

	@Bean
	RabbitEventPublisher eventPublisher(RabbitTemplate rabbitTemplate, EventBusProperties properties, EventsJson json) {
		return new RabbitEventPublisher(rabbitTemplate, properties, json);
	}

	@Bean
	EventConsumerRegistry eventConsumerRegistry(List<EventConsumer<?>> consumers) {
		return new EventConsumerRegistry(consumers);
	}

	@Bean
	EventConsumerDispatcher eventConsumerDispatcher(EventConsumerRegistry registry, EventsJson json) {
		return new EventConsumerDispatcher(registry, json);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.events", name = "listen", havingValue = "true")
	Declarables eventDeclarables(EventBusProperties properties, TopicExchange exchange) {
		Queue queue = new Queue(properties.getQueue(), true, false, false);
		List<Declarable> declarables = new ArrayList<>();
		declarables.add(queue);
		for (String bindingKey : properties.getBindings()) {
			Binding binding = BindingBuilder.bind(queue).to(exchange).with(bindingKey);
			declarables.add(binding);
		}
		return new Declarables(declarables);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.events", name = "listen", havingValue = "true")
	SimpleMessageListenerContainer eventListenerContainer(ConnectionFactory connectionFactory,
			EventBusProperties properties, EventConsumerDispatcher dispatcher, EventsJson json) {
		SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
		container.setQueueNames(properties.getQueue());
		container.setMessageListener((MessageListener) message -> {
			dispatcher.dispatch(json.readEnvelope(message.getBody()));
		});
		return container;
	}
}