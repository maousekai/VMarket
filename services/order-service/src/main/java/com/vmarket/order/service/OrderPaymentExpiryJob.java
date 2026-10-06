package com.vmarket.order.service;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import com.vmarket.order.repository.OrderRepository;
import com.vmarket.order.entity.OrderStatus;
import lombok.RequiredArgsConstructor;
@Component @RequiredArgsConstructor
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class OrderPaymentExpiryJob {
    private final OrderRepository repository; private final OrderPaymentLifecycle lifecycle;
    @Scheduled(fixedDelayString = "${app.payment.expiry-delay:10000}") public void run() {
        var now = Instant.now();
        for (var order : repository.findByStatusAndPaymentExpiresAtLessThanEqual(OrderStatus.WAITING_PAYMENT, now, PageRequest.of(0, 100))) lifecycle.expire(order.getId(), now);
    }
}
