package com.vmarket.review.service;

import java.sql.Timestamp;
import java.time.Clock;

import com.github.f4b6a3.ulid.UlidCreator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.events.EventType;

import lombok.RequiredArgsConstructor;

/**
 * Ghi yêu cầu phát {@code ReviewCreated} vào bảng outbox trong CÙNG transaction với thay đổi đánh
 * giá — đánh giá đã lưu thì chắc chắn sẽ có sự kiện, kể cả khi RabbitMQ đang chết.
 *
 * <p>Dòng outbox chỉ chứa {@code productId}; điểm tổng hợp được tính lại lúc phát (xem
 * {@link ReviewOutboxPublisher}).
 */
@Component
@RequiredArgsConstructor
public class ReviewOutbox {

	private final JdbcTemplate jdbc;
	private final Clock clock;

	@Transactional(propagation = Propagation.MANDATORY)
	public void ratingChanged(String reviewId, String productId) {
		Timestamp now = Timestamp.from(clock.instant());
		jdbc.update("insert into review_outbox (id, event_type, review_id, product_id, state, retry_attempts,"
				+ " next_attempt_at, created_at) values (?, ?, ?, ?, 'PENDING', 0, ?, ?)",
				UlidCreator.getUlid().toString(), EventType.REVIEW_CREATED, reviewId, productId, now, now);
	}
}
