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
 * <p><b>Aggregate tính lúc phát, dưới khoá theo sản phẩm, kèm revision tăng dần.</b> Mỗi dòng được
 * claim bằng {@code FOR UPDATE SKIP LOCKED} (nhiều replica chia nhau hàng đợi), sau đó publisher
 * {@code UPDATE product_rating_revisions SET revision = revision + 1} cho sản phẩm đó — câu UPDATE
 * giữ khoá dòng tới hết transaction và cấp revision mới. Hai replica xử lý cùng sản phẩm vì vậy chạy
 * tuần tự, và bản tin tính sau luôn có revision lớn hơn. Product Service so <b>revision</b> (không
 * so timestamp envelope — timestamp lấy từ đồng hồ từng replica, có thể lệch/lùi), nên aggregate mới
 * không bị bỏ qua và bản tin cũ/phát lại không ghi đè được bản mới.
 *
 * <p>Publish lỗi thì revision đã cấp vẫn được commit cùng lúc ghi nhận lỗi — revision có thể nhảy
 * cóc nhưng luôn tăng, đúng yêu cầu của consumer.
 *
 * <p>Transaction và các khoá được giữ trong lúc chờ broker confirm (tối đa
 * {@code app.events.confirm-timeout-millis}, xử lý tuần tự tối đa {@value #BATCH_LIMIT} dòng mỗi
 * lượt). Chấp nhận được với lưu lượng đánh giá thấp; khoá chỉ chặn publisher khác của
 * <b>cùng sản phẩm</b>, không chặn request ghi đánh giá.
 *
 * <p>Lỗi phát → thử lại với backoff luỹ thừa (5s → tối đa 5 phút); quá {@link #MAX_ATTEMPTS} lần
 * thì chuyển {@code DEAD} để không chặn hàng đợi. Dòng DEAD được báo qua metric/log
 * ({@link ReviewOutboxMonitor}) và phát lại qua {@code /api/reviews/admin/outbox}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.review.outbox-enabled", havingValue = "true", matchIfMissing = true)
public class ReviewOutboxPublisher {

	public static final int MAX_ATTEMPTS = 10;
	private static final int BATCH_LIMIT = 100;
	private static final int MAX_ERROR_LENGTH = 500;

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

	/** Phát một dòng đến hạn trong transaction hiện tại; trả {@code false} khi không còn dòng nào. */
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
		long revision = lockAndNextRevision(productId);
		try {
			RatingSummary summary = ratings.productSummary(productId);
			publisher.publish(EventType.REVIEW_CREATED, new ReviewCreated(
					(String) row.get("review_id"), productId, summary.average(), summary.count(), revision));
			jdbc.update("delete from review_outbox where id = ?", id);
		} catch (RuntimeException ex) {
			int attempts = ((Number) row.get("retry_attempts")).intValue() + 1;
			long delaySeconds = Math.min(300, 5L << Math.min(attempts - 1, 6));
			String state = attempts >= MAX_ATTEMPTS ? "DEAD" : "PENDING";
			jdbc.update("update review_outbox set retry_attempts = ?, next_attempt_at = ?, state = ?, last_error = ?"
					+ " where id = ?", attempts, Timestamp.from(clock.instant().plusSeconds(delaySeconds)), state,
					describe(ex), id);
			log.warn("ReviewCreated outbox {} -> {} sau {} lần thử: {}", id, state, attempts, describe(ex));
		}
		return true;
	}

	/**
	 * Khoá dòng của sản phẩm tới hết transaction và cấp revision kế tiếp. {@code ON CONFLICT DO
	 * NOTHING} tạo dòng ở lần đầu mà không lỗi khi hai replica cùng tạo; {@code UPDATE} lấy khoá ghi
	 * nên replica khác (cùng sản phẩm) phải đợi tới khi transaction này commit.
	 */
	private long lockAndNextRevision(String productId) {
		jdbc.update("insert into product_rating_revisions (product_id, revision) values (?, 0) on conflict do nothing",
				productId);
		jdbc.update("update product_rating_revisions set revision = revision + 1 where product_id = ?", productId);
		Long revision = jdbc.queryForObject("select revision from product_rating_revisions where product_id = ?",
				Long.class, productId);
		return revision == null ? 0 : revision;
	}

	/** Tên lớp + message đã cắt ngắn để debug được mà không phình cột. */
	static String describe(Throwable ex) {
		String text = ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
		return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
	}
}
