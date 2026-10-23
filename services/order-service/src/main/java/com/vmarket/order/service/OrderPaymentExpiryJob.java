package com.vmarket.order.service;

import com.vmarket.order.config.OrderPaymentProperties;
import com.vmarket.order.entity.OrderStatus;
import com.vmarket.order.repository.OrderRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class OrderPaymentExpiryJob {
    private final OrderRepository repository;
    private final OrderPaymentLifecycle lifecycle;
    private final Clock clock;
    private final OrderPaymentProperties timing;

    @Scheduled(fixedDelayString = "${app.payment.expiry-delay:10000}")
    public void run() {
        var now = clock.instant();
        for (var order :
                repository.findByStatusAndPaymentExpiresAtLessThanEqual(
                        OrderStatus.WAITING_PAYMENT,
                        now.minus(timing.getFallbackDelay()),
                        PageRequest.of(0, 100))) {
            try {
                lifecycle.expire(order.getId(), now);
            } catch (RuntimeException failure) {
                log.warn(
                        "Order payment fallback deferred for {}: {}",
                        order.getId(),
                        failure.getClass().getSimpleName());
            }
        }
    }
}
