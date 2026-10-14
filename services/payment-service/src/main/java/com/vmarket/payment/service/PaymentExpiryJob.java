package com.vmarket.payment.service;
import java.time.Clock;
import java.util.List;
import com.vmarket.payment.repository.PaymentRepository;
import com.vmarket.payment.client.PayosClient;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
@Component @RequiredArgsConstructor @Slf4j
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentExpiryJob {
    private final PaymentRepository repository; private final PaymentStore store; private final PayosClient client; private final Clock clock;
    @Scheduled(fixedDelayString = "${app.payment.expiry-delay:10000}")
    public void run() {
        for (var p : repository.findByStatusInAndExpiresAtLessThanEqual(List.of("CREATED", "PENDING", "FAILED"), clock.instant(), PageRequest.of(0, 100))) store.expire(p.getId());
        for (var p : repository.findByCancelPendingTrueAndNextCancelAttemptAtLessThanEqualOrderByNextCancelAttemptAtAscIdAsc(clock.instant(), PageRequest.of(0, 100))) {
            try { store.cancelRemote(p.getId(), client); } catch (com.vmarket.payment.exception.ApiException ex) { log.warn("Provider cancellation pending for payment {}", p.getId()); }
        }
    }
}
