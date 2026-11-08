package com.vmarket.review.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.review.exception.ApiException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Xem và phát lại dòng outbox {@code DEAD} (broker lỗi quá {@link ReviewOutboxPublisher#MAX_ATTEMPTS}
 * lần). Phát lại = đưa về {@code PENDING}, đến hạn ngay; publisher sẽ tính aggregate MỚI NHẤT nên
 * phát lại muộn cũng không làm điểm sản phẩm lùi về giá trị cũ.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewOutboxRecovery {

	private final JdbcTemplate jdbc;
	private final Clock clock;

	public record Status(long pending, long dead, long oldestDeadAgeSeconds) {
	}

	public record DeadEvent(String id, String reviewId, String productId, int retryAttempts, String lastError,
			Instant createdAt) {
	}

	@Transactional(readOnly = true)
	public Status status() {
		long pending = count("PENDING");
		long dead = count("DEAD");
		Timestamp oldest = jdbc.queryForObject("select min(created_at) from review_outbox where state = 'DEAD'",
				Timestamp.class);
		long age = oldest == null ? 0 : Math.max(0, Duration.between(oldest.toInstant(), clock.instant()).toSeconds());
		return new Status(pending, dead, age);
	}

	@Transactional(readOnly = true)
	public List<DeadEvent> dead(int page, int size) {
		return jdbc.query("select id, review_id, product_id, retry_attempts, last_error, created_at from review_outbox"
				+ " where state = 'DEAD' order by created_at, id limit ? offset ?",
				(rs, n) -> new DeadEvent(rs.getString("id"), rs.getString("review_id"), rs.getString("product_id"),
						rs.getInt("retry_attempts"), rs.getString("last_error"),
						rs.getTimestamp("created_at").toInstant()),
				size, (long) page * size);
	}

	@Transactional
	public void replay(String id, String admin, String reason) {
		if (requeue("id = ?", id) == 0) {
			throw ApiException.notFound("OUTBOX_EVENT_NOT_FOUND", "Không có sự kiện DEAD với id này");
		}
		log.info("Review outbox {} replayed by {}: {}", id, admin, reason);
	}

	@Transactional
	public int replayAll(String admin, String reason) {
		int replayed = requeue("1 = 1");
		log.info("Review outbox: {} DEAD events replayed by {}: {}", replayed, admin, reason);
		return replayed;
	}

	private int requeue(String condition, Object... args) {
		Object[] params = new Object[args.length + 1];
		params[0] = Timestamp.from(clock.instant());
		System.arraycopy(args, 0, params, 1, args.length);
		return jdbc.update("update review_outbox set state = 'PENDING', retry_attempts = 0, next_attempt_at = ?,"
				+ " last_error = null where state = 'DEAD' and " + condition, params);
	}

	private long count(String state) {
		Long n = jdbc.queryForObject("select count(*) from review_outbox where state = ?", Long.class, state);
		return n == null ? 0 : n;
	}
}
