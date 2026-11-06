package com.vmarket.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

/**
 * Queue {@code notification-service.delivery-assigned} PHẢI được khai báo với đúng tham số
 * như delivery-service (DeliveryEventTopology) — lệch tham số thì RabbitMQ từ chối khai báo
 * lại queue (PRECONDITION_FAILED) và listener không khởi động được.
 */
class DeliveryAssignedListenerConfigTest {

	@Test
	void declaresSameQueueAsDeliveryService() {
		Declarables declarables = new DeliveryAssignedListenerConfig()
				.deliveryAssignedQueue(new TopicExchange("vmarket.events", true, false));

		Queue queue = declarables.getDeclarablesByType(Queue.class).stream()
				.filter(q -> q.getName().equals("notification-service.delivery-assigned"))
				.findFirst().orElseThrow();
		assertThat(queue.isDurable()).isTrue();
		assertThat(queue.getArguments()).isEqualTo(Map.of(
				"x-dead-letter-exchange", "vmarket.events.dlx",
				"x-dead-letter-routing-key", "notification-service.delivery-assigned.dead"));

		assertThat(declarables.getDeclarablesByType(Binding.class))
				.anySatisfy(binding -> {
					assertThat(binding.getDestination()).isEqualTo("notification-service.delivery-assigned");
					assertThat(binding.getExchange()).isEqualTo("vmarket.events");
					assertThat(binding.getRoutingKey()).isEqualTo("DeliveryAssigned");
				});
	}
}
