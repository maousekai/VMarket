package com.vmarket.order.event;

import com.vmarket.events.EventPublisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxDispatcher {
    private final OutboxRepository repository;
    private final EventPublisher publisher;
    private final ObjectMapper mapper;

    @Transactional
    public void dispatch() {
        for (var event : repository.next(Instant.now())) {
            try {
                publisher.publish(event.getEventType(), mapper.readTree(event.getPayload()));
                repository.delete(event);
            } catch (Exception failure) {
                int attempts = event.getRetryAttempts() + 1;
                event.setRetryAttempts(attempts);
                event.setNextAttemptAt(
                        Instant.now().plusSeconds(Math.min(300, 5L << Math.min(attempts - 1, 6))));
                event.setLastError(failure.getClass().getSimpleName());
                if (attempts >= 10) event.setState("DEAD");
                log.warn("Order outbox {} after {} attempts", event.getState(), attempts);
            }
        }
    }
}
