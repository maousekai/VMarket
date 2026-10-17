package com.vmarket.order.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.amqp.AmqpException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxJob {
    private final OutboxDispatcher dispatcher;

    @Scheduled(fixedDelayString = "${app.outbox.delay:1000}")
    public void run() {
        try {
            dispatcher.dispatch();
        } catch (AmqpException ex) {
            log.warn("Broker unavailable; outbox retained");
        }
    }
}
