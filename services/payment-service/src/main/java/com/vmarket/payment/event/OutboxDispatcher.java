package com.vmarket.payment.event;

import com.vmarket.events.EventPublisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxDispatcher {
    private final OutboxRepository repository;
    private final EventPublisher publisher;
    private final ObjectMapper mapper;
    private final PlatformTransactionManager transactions;

    public void dispatch() {
        // Commit each ACK/delete or retry independently; only one outbox row is locked.
        for (int processed = 0; processed < 50; processed++) {
            if (!Boolean.TRUE.equals(
                    new TransactionTemplate(transactions).execute(ignored -> dispatchOne()))) break;
        }
    }

    private boolean dispatchOne() {
        var rows = repository.next(Instant.now());
        if (rows.isEmpty()) return false;
        var event = rows.get(0);
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
            log.warn("payment outbox {} after {} attempts", event.getState(), attempts);
        }
        return true;
    }
}
