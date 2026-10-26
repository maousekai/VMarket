package com.vmarket.order.event;

import io.micrometer.core.instrument.MeterRegistry;

import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxMonitor {
    private final OutboxRecovery recovery;
    private volatile OutboxRecovery.Status current = new OutboxRecovery.Status(0, 0, 0);

    public OutboxMonitor(OutboxRecovery recovery, MeterRegistry metrics) {
        this.recovery = recovery;
        metrics.gauge("vmarket.order.outbox.dead", this, m -> m.current.dead());
        metrics.gauge("vmarket.order.outbox.blocked", this, m -> m.current.blocked());
        metrics.gauge(
                "vmarket.order.outbox.oldest.dead.age",
                this,
                m -> m.current.oldestDeadAgeSeconds());
    }

    @Scheduled(fixedDelayString = "${app.outbox.monitor-delay:60000}")
    public void inspect() {
        current = recovery.status();
        if (current.dead() > 0)
            log.error(
                    "ORDER_OUTBOX_DEAD dead={} blocked={} oldestAgeSeconds={}; inspect"
                        + " /api/orders/admin/outbox/dead",
                    current.dead(),
                    current.blocked(),
                    current.oldestDeadAgeSeconds());
    }
}
