package com.vmarket.delivery.config;

import com.vmarket.events.EventType;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Retain assignment notifications durably until Notification Service starts consuming them. */
@Configuration
public class DeliveryEventTopology {
    @Bean
    Declarables deliveryAssignmentNotifications(TopicExchange eventExchange) {
        String queueName = "notification-service.delivery-assigned";
        var queue =
                QueueBuilder.durable(queueName)
                        .deadLetterExchange(eventExchange.getName() + ".dlx")
                        .deadLetterRoutingKey(queueName + ".dead")
                        .build();
        var deadExchange = new TopicExchange(eventExchange.getName() + ".dlx", true, false);
        var deadQueue = QueueBuilder.durable(queueName + ".dead").build();
        return new Declarables(
                queue,
                deadExchange,
                deadQueue,
                BindingBuilder.bind(queue).to(eventExchange).with(EventType.DELIVERY_ASSIGNED),
                BindingBuilder.bind(deadQueue).to(deadExchange).with(queueName + ".dead"));
    }
}
