package com.vmarket.payment.event;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
@Component @RequiredArgsConstructor @Slf4j
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxJob {
    private final OutboxDispatcher dispatcher;
    @Scheduled(fixedDelayString = "${app.outbox.delay:1000}")
    public void run() {
        try { dispatcher.dispatch(); } catch (org.springframework.amqp.AmqpException ex) { log.warn("Broker unavailable; outbox retained"); }
    }
}
