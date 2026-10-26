package com.vmarket.payment.service;

import com.vmarket.payment.client.PayosClient;
import com.vmarket.payment.repository.PaymentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentExpiryJob {
    private final PaymentRepository repository;
    private final PaymentStore store;
    private final PayosClient client;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${app.payment.expiry-delay:10000}")
    public void run() {
        for (var p :
                repository
                        .findByStatusInAndSettlementExpiresAtLessThanEqualOrderBySettlementExpiresAtAscIdAsc(
                                List.of(
                                        "CREATED",
                                        "CREATING",
                                        "PENDING",
                                        "FAILED",
                                        "AWAITING_WEBHOOK"),
                                clock.instant(),
                                PageRequest.of(0, 100))) {
            try {
                store.expire(p.getId());
            } catch (RuntimeException failure) {
                log.warn(
                        "Payment expiry deferred for {}: {}",
                        p.getId(),
                        failure.getClass().getSimpleName());
            }
        }
        for (var p :
                repository
                        .findByCancelPendingTrueAndNextCancelAttemptAtLessThanEqualOrderByNextCancelAttemptAtAscIdAsc(
                                clock.instant(), PageRequest.of(0, 100))) {
            try {
                store.cancelRemote(p.getId(), client);
            } catch (RuntimeException ex) {
                log.warn("Provider cancellation pending for payment {}", p.getId());
            }
        }
    }
}
