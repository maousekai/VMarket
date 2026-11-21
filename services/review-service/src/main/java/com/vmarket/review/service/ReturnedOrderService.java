package com.vmarket.review.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.review.entity.Review;
import com.vmarket.review.entity.ReviewStatus;
import com.vmarket.review.repository.ReviewRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Đánh giá chỉ có giá trị từ khi nhận hàng tới trước khi trả hàng (PBL6-35).
 *
 * <p>Khi yêu cầu trả hàng / hoàn tiền được chốt ({@code ReturnResolved}), từng sản phẩm của yêu cầu
 * được ghi vào {@code returned_items} (chặn viết đánh giá mới cho sản phẩm đó trong đơn) và đánh giá
 * của các sản phẩm đó chuyển sang {@link ReviewStatus#RETURNED}. Áp dụng theo sản phẩm chứ không theo
 * cả đơn: đơn tạo bằng {@code POST /api/orders} có thể gồm nhiều gian hàng, còn mỗi yêu cầu trả hàng
 * chỉ thuộc một gian hàng. Đánh giá đang hiển thị thì ghi outbox để Product Service nhận điểm trung
 * bình mới. Xử lý lặp (at-least-once) không đổi kết quả.
 *
 * <p>Gửi đánh giá và chốt trả hàng cùng giữ {@link #lockOrder khoá theo đơn} trong transaction của
 * mình, nên bên chạy sau luôn thấy dữ liệu đã commit của bên chạy trước.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReturnedOrderService {

	static final String RETURNED_REASON = "Sản phẩm đã được trả hàng / hoàn tiền";

	private final JdbcTemplate jdbc;
	private final ReviewRepository reviews;
	private final ReviewOutbox outbox;
	private final Clock clock;

	/**
	 * Sản phẩm của đơn đã trả hàng. Đơn trả hàng ghi theo kiểu cũ (chỉ biết đơn, {@code legacy_returned_orders})
	 * chưa được đối chiếu thì chặn cả đơn như trước.
	 */
	@Transactional(readOnly = true)
	public boolean isReturned(String orderId, String productId) {
		Integer found = jdbc.queryForObject("""
				select (select count(*) from returned_items where order_id = ? and product_id = ?)
				     + (select count(*) from legacy_returned_orders where order_id = ?)""", Integer.class, orderId,
				productId, orderId);
		return found != null && found > 0;
	}

	/** Đơn trả hàng ghi theo kiểu cũ còn chờ đối chiếu với Order Service. */
	@Transactional(readOnly = true)
	public List<LegacyReturn> legacyReturns(int limit) {
		return jdbc.query("select order_id, return_id from legacy_returned_orders order by resolved_at, order_id limit ?",
				(rs, row) -> new LegacyReturn(rs.getString("order_id"), rs.getString("return_id")), limit);
	}

	/**
	 * Chuyển một đơn trả hàng kiểu cũ sang theo sản phẩm: {@code returnedProducts} là sản phẩm trong các
	 * yêu cầu trả hàng {@code RESOLVED} của đơn (theo Order Service). Đánh giá của các sản phẩm đó giữ
	 * {@code RETURNED}; đánh giá của sản phẩm khác bị bản cũ ẩn nhầm (cả đơn) được hiển thị lại.
	 */
	@Transactional
	public void reconcileLegacy(LegacyReturn legacy, Collection<String> returnedProducts) {
		lockOrder(legacy.orderId());
		Set<String> products = new LinkedHashSet<>(returnedProducts);
		Instant now = clock.instant();
		for (String productId : products) {
			jdbc.update("insert into returned_items (order_id, product_id, return_id, resolved_at) values (?, ?, ?, ?)"
					+ " on conflict do nothing", legacy.orderId(), productId, legacy.returnId(), Timestamp.from(now));
		}
		int restored = 0;
		for (Review review : reviews.findByOrderId(legacy.orderId())) {
			if (review.getStatus() != ReviewStatus.RETURNED || products.contains(review.getProductId())
					|| isReturnedItem(legacy.orderId(), review.getProductId())) {
				continue;
			}
			review.setStatus(ReviewStatus.VISIBLE);
			review.setModerationReason(null);
			review.setModeratedBy(null);
			review.setModeratedAt(null);
			review.setUpdatedAt(now);
			reviews.saveAndFlush(review);
			outbox.ratingChanged(review.getId(), review.getProductId());
			restored++;
		}
		jdbc.update("delete from legacy_returned_orders where order_id = ?", legacy.orderId());
		log.info("Đối chiếu đơn trả hàng cũ {}: {} sản phẩm đã trả, hiển thị lại {} đánh giá", legacy.orderId(),
				products.size(), restored);
	}

	private boolean isReturnedItem(String orderId, String productId) {
		Integer found = jdbc.queryForObject(
				"select count(*) from returned_items where order_id = ? and product_id = ?", Integer.class, orderId,
				productId);
		return found != null && found > 0;
	}

	public record LegacyReturn(String orderId, String returnId) {
	}

	/**
	 * Khoá theo đơn, giữ tới hết transaction hiện tại. Dòng khoá được tạo ở lần đầu; transaction thứ
	 * hai chèn cùng đơn phải chờ transaction đầu kết thúc, sau đó {@code for update} xếp hàng các lần
	 * sau.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void lockOrder(String orderId) {
		jdbc.update("insert into review_order_locks (order_id) values (?) on conflict do nothing", orderId);
		jdbc.queryForList("select order_id from review_order_locks where order_id = ? for update", String.class,
				orderId);
	}

	@Transactional
	public void markReturned(String orderId, String returnId, Collection<String> productIds) {
		Set<String> products = new LinkedHashSet<>();
		if (productIds != null) {
			productIds.stream().filter(Objects::nonNull).forEach(products::add);
		}
		if (products.isEmpty()) {
			log.warn("Yêu cầu trả hàng {} của đơn {} không có sản phẩm nào: bỏ qua", returnId, orderId);
			return;
		}
		lockOrder(orderId);
		Instant now = clock.instant();
		for (String productId : products) {
			jdbc.update("insert into returned_items (order_id, product_id, return_id, resolved_at) values (?, ?, ?, ?)"
					+ " on conflict do nothing", orderId, productId, returnId, Timestamp.from(now));
		}
		int changed = 0;
		for (Review review : reviews.findByOrderId(orderId)) {
			if (review.getStatus() == ReviewStatus.RETURNED || !products.contains(review.getProductId())) {
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
		log.info("Đơn {} trả hàng / hoàn tiền {} sản phẩm (return {}): ẩn {} đánh giá", orderId, products.size(),
				returnId, changed);
	}
}
