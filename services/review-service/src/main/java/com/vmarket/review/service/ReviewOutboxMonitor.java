package com.vmarket.review.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Metric + cảnh báo cho outbox (giống {@code OutboxMonitor} của order-service):
 * {@code vmarket.review.outbox.dead}, {@code .pending}, {@code .oldest.dead.age} xuất ra
 * {@code /actuator/prometheus} để Grafana đặt alert; có dòng DEAD thì log ERROR mỗi lượt kiểm tra.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.review.outbox-monitor-enabled", havingValue = "true", matchIfMissing = true)
public class ReviewOutboxMonitor {

	private final ReviewOutboxRecovery recovery;
	private volatile ReviewOutboxRecovery.Status current = new ReviewOutboxRecovery.Status(0, 0, 0);

	public ReviewOutboxMonitor(ReviewOutboxRecovery recovery, MeterRegistry metrics) {
		this.recovery = recovery;
		metrics.gauge("vmarket.review.outbox.dead", this, m -> m.current.dead());
		metrics.gauge("vmarket.review.outbox.pending", this, m -> m.current.pending());
		metrics.gauge("vmarket.review.outbox.oldest.dead.age", this, m -> m.current.oldestDeadAgeSeconds());
	}

	@Scheduled(fixedDelayString = "${app.review.outbox-monitor-delay:60000}")
	public void inspect() {
		current = recovery.status();
		if (current.dead() > 0) {
			log.error("REVIEW_OUTBOX_DEAD dead={} pending={} oldestAgeSeconds={}; xem /api/reviews/admin/outbox/dead",
					current.dead(), current.pending(), current.oldestDeadAgeSeconds());
		}
	}
}
