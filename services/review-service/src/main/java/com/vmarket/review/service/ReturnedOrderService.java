package com.vmarket.review.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.review.entity.Review;
import com.vmarket.review.entity.ReviewStatus;
import com.vmarket.review.repository.ReviewRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Đánh giá chỉ có giá trị từ khi nhận hàng tới trước khi trả hàng (PBL6-35).
 *
 * <p>Khi yêu cầu trả hàng / hoàn tiền của một đơn được chốt ({@code ReturnResolved}), đơn được ghi vào
 * {@code returned_orders} (chặn viết đánh giá mới) và mọi đánh giá của đơn chuyển sang
 * {@link ReviewStatus#RETURNED}. Đánh giá đang hiển thị thì ghi outbox để Product Service nhận điểm
 * trung bình mới. Order Service bắt buộc trả nguyên đơn ({@code WHOLE_ORDER_RETURN_REQUIRED}) nên áp
 * dụng cho mọi sản phẩm trong đơn. Xử lý lặp (at-least-once) không đổi kết quả.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReturnedOrderService {

	static final String RETURNED_REASON = "Đơn hàng đã được trả hàng / hoàn tiền";

	private final JdbcTemplate jdbc;
	private final ReviewRepository reviews;
	private final ReviewOutbox outbox;
	private final Clock clock;

	@Transactional(readOnly = true)
	public boolean isReturned(String orderId) {
		Integer found = jdbc.queryForObject("select count(*) from returned_orders where order_id = ?", Integer.class,
				orderId);
		return found != null && found > 0;
	}

	@Transactional
	public void markReturned(String orderId, String returnId) {
		Instant now = clock.instant();
		try {
			jdbc.update("insert into returned_orders (order_id, return_id, resolved_at) select ?, ?, ?"
					+ " where not exists (select 1 from returned_orders where order_id = ?)", orderId, returnId,
					Timestamp.from(now), orderId);
		} catch (DuplicateKeyException ex) {
			// Hai lần giao cùng sự kiện chạy song song: bản kia đã ghi đơn này.
			log.debug("Đơn {} đã được ghi nhận trả hàng", orderId);
		}
		int changed = 0;
		for (Review review : reviews.findByOrderId(orderId)) {
			if (review.getStatus() == ReviewStatus.RETURNED) {
				continue;
			}
			boolean wasVisible = review.isVisible();
			review.setStatus(ReviewStatus.RETURNED);
			review.setModerationReason(RETURNED_REASON);
			review.setModeratedBy(null);
			review.setModeratedAt(now);
			review.setUpdatedAt(now);
			reviews.saveAndFlush(review);
			if (wasVisible) {
				outbox.ratingChanged(review.getId(), review.getProductId());
			}
			changed++;
		}
		log.info("Đơn {} đã trả hàng / hoàn tiền (return {}): ẩn {} đánh giá", orderId, returnId, changed);
	}
}
