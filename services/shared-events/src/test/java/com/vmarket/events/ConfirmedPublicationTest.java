package com.vmarket.events;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.vmarket.events.config.EventBusProperties;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class ConfirmedPublicationTest {
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);

    private RabbitEventPublisher publisher() {
        EventBusProperties properties = new EventBusProperties();
        properties.setConfirmedPublication(true);
        properties.setConfirmTimeoutMillis(10);
        return new RabbitEventPublisher(rabbit, properties, new EventsJson());
    }

    private void confirm(boolean ack, boolean returned) {
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            if (returned) correlation.setReturned(new ReturnedMessage(call.getArgument(2), 312,
                    "NO_ROUTE", "vmarket.events", "CodCollected"));
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, ack ? null : "Rejected"));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    @Test void brokerAckCompletesPublication() {
        confirm(true, false);
        publisher().publish(EventType.COD_COLLECTED, new CodCollected("o", "b", 100000, "a"));
        verify(rabbit).setMandatory(true);
    }

    @Test void brokerNackAndUnroutableAckCannotDeleteAnOutboxRow() {
        confirm(false, false);
        assertThatThrownBy(() -> publisher().publish("Test", "payload")).isInstanceOf(AmqpException.class);
        confirm(true, true);
        assertThatThrownBy(() -> publisher().publish("Test", "payload")).isInstanceOf(AmqpException.class);
    }

    @Test void missingConfirmationFailsInsteadOfReportingSuccess() {
        assertThatThrownBy(() -> publisher().publish("Test", "payload")).isInstanceOf(AmqpException.class);
    }
}
