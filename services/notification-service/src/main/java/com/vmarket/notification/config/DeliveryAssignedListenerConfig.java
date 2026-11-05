package com.vmarket.notification.config;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.vmarket.events.EventConsumerDispatcher;
import com.vmarket.events.EventType;
import com.vmarket.events.EventsJson;

/**
 * Nhận {@code DeliveryAssigned} từ queue {@value #QUEUE}.
 *
 * <p>delivery-service đã khai báo sẵn queue durable này (kèm DLX) để publisher confirms
 * có route ngay cả khi Notification chưa chạy — sự kiện phát trước đó vẫn nằm chờ trong
 * queue. Vì vậy Notification tiêu thụ ĐÚNG queue đó thay vì bind {@code DeliveryAssigned}
 * vào queue chính (sẽ nhận trùng và để queue cũ phình mãi).
 *
 * <p>Khai báo lại queue với đúng tham số như delivery-service (khai báo trùng là
 * idempotent) để Notification khởi động được cả khi Delivery chưa từng chạy. Retry/DLQ
 * giống listener chính của thư viện shared-events.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.events", name = "listen", havingValue = "true")
public class DeliveryAssignedListenerConfig {

	public static final String QUEUE = "notification-service.delivery-assigned";

	@Bean
	Declarables deliveryAssignedQueue(TopicExchange eventExchange) {
		String deadLetterExchange = eventExchange.getName() + ".dlx";
		var queue = QueueBuilder.durable(QUEUE)
				.deadLetterExchange(deadLetterExchange)
				.deadLetterRoutingKey(QUEUE + ".dead")
				.build();
		var deadExchange = new TopicExchange(deadLetterExchange, true, false);
		var deadQueue = QueueBuilder.durable(QUEUE + ".dead").build();
		return new Declarables(
				queue,
				deadExchange,
				deadQueue,
				BindingBuilder.bind(queue).to(eventExchange).with(EventType.DELIVERY_ASSIGNED),
				BindingBuilder.bind(deadQueue).to(deadExchange).with(QUEUE + ".dead"));
	}

	@Bean
	SimpleMessageListenerContainer deliveryAssignedListenerContainer(ConnectionFactory connectionFactory,
			EventConsumerDispatcher dispatcher, EventsJson json) {
		SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
		container.setQueueNames(QUEUE);
		container.setDefaultRequeueRejected(false);
		container.setAdviceChain(RetryInterceptorBuilder.stateless()
				.maxRetries(2)
				.backOffOptions(1000, 2, 10000)
				.recoverer(new RejectAndDontRequeueRecoverer())
				.build());
		container.setMessageListener((MessageListener) message -> dispatcher.dispatch(json.readEnvelope(message.getBody())));
		return container;
	}
}
