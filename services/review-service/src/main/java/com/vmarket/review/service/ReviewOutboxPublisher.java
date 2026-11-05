package com.vmarket.review.service;

import java.sql.Timestamp;
import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.vmarket.events.EventPublisher;
import com.vmarket.events.EventType;
import com.vmarket.events.ReviewCreated;
import com.vmarket.review.dto.RatingSummary;

import lombok.extern.slf4j.Slf4j;

/**
 * Đẩy dòng outbox lên Event Bus (FR-REV-02).
 *
 * <p><b>Aggregate tính lúc phát, không lúc ghi:</b> hai đánh giá cùng sản phẩm commit gần như đồng
 * thời sẽ mỗi bên đọc thiếu bản ghi của bên kia nếu tính trong transaction ghi. Tính lại ở đây (sau
 * commit) thì bản tin phát sau cùng luôn mang số liệu đầy đủ; Product Service nhận aggregate tuyệt
 * đối nên xử lý lặp/không theo thứ tự đều hội tụ đúng.
 *
 * <p>Lỗi phát → thử lại với backoff luỹ thừa (5s → tối đa 5 phút); quá {@link #MAX_ATTEMPTS} lần
 * thì đánh dấu {@code DEAD} để không chặn hàng đợi.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.review.outbox-enabled", havingValue = "true", matchIfMissing = true)
public class ReviewOutboxPublisher {

	public static final int MAX_ATTEMPTS = 10;
	private static final int BATCH_LIMIT = 100;

	private final JdbcTemplate jdbc;
	private final EventPublisher publisher;
	private final RatingService ratings;
	private final Clock clock;
	private final TransactionTemplate transactions;

	public ReviewOutboxPublisher(JdbcTemplate jdbc, EventPublisher publisher, RatingService ratings, Clock clock,
			PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.publisher = publisher;
		this.ratings = ratings;
		this.clock = clock;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	@Scheduled(fixedDelayString = "${app.review.outbox-delay:1000}")
	public void flush() {
		for (int i = 0; i < BATCH_LIMIT; i++) {
			if (!Boolean.TRUE.equals(transactions.execute(status -> publishOne()))) {
				return;
			}
		}
	}

	/** Phát một dòng đến hạn; trả {@code false} khi không còn dòng nào. */
	public boolean publishOne() {
		var rows = jdbc.queryForList("select id, review_id, product_id, retry_attempts from review_outbox"
				+ " where state = 'PENDING' and next_attempt_at <= ?"
				+ " order by next_attempt_at, created_at, id limit 1 for update skip locked",
				Timestamp.from(clock.instant()));
		if (rows.isEmpty()) {
			return false;
		}
		var row = rows.get(0);
		String id = (String) row.get("id");
		String productId = (String) row.get("product_id");
		try {
			RatingSummary summary = ratings.productSummary(productId);
			publisher.publish(EventType.REVIEW_CREATED, new ReviewCreated(
					(String) row.get("review_id"), productId, summary.average(), summary.count()));
			jdbc.update("delete from review_outbox where id = ?", id);
		} catch (RuntimeException ex) {
			int attempts = ((Number) row.get("retry_attempts")).intValue() + 1;
			long delaySeconds = Math.min(300, 5L << Math.min(attempts - 1, 6));
			String state = attempts >= MAX_ATTEMPTS ? "DEAD" : "PENDING";
			jdbc.update("update review_outbox set retry_attempts = ?, next_attempt_at = ?, state = ?, last_error = ?"
					+ " where id = ?", attempts, Timestamp.from(clock.instant().plusSeconds(delaySeconds)), state,
					ex.getClass().getSimpleName(), id);
			log.warn("ReviewCreated outbox {} -> {} sau {} lần thử", id, state, attempts);
		}
		return true;
	}
}
