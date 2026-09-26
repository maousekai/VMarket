package com.vmarket.shop.event;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.vmarket.events.EventPublisher;
import com.vmarket.events.EventType;
import com.vmarket.events.ShopApproved;
import com.vmarket.events.ShopSuspended;
import com.vmarket.shop.entity.ShopAction;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Cổng phát sự kiện gian hàng lên Event Bus (FR-SHOP-04, SRS §8.1).
 *
 * <table>
 *   <tr><th>Hành động</th><th>Sự kiện</th></tr>
 *   <tr><td>APPROVE</td><td>{@code ShopApproved} ({@code reinstated = false})</td></tr>
 *   <tr><td>REINSTATE</td><td>{@code ShopApproved} ({@code reinstated = true})</td></tr>
 *   <tr><td>SUSPEND</td><td>{@code ShopSuspended}</td></tr>
 *   <tr><td>REJECT, RESUBMIT</td><td>không phát — SRS không định nghĩa sự kiện cho hai bước này</td></tr>
 * </table>
 *
 * <p><b>Phát SAU KHI commit</b> ({@code AFTER_COMMIT}), không phát trong transaction:
 * phát trước commit mà commit lại thất bại (vd xung đột {@code @Version}) thì Auth đã
 * cấp vai trò SELLER cho một gian hàng thực tế chưa từng được duyệt — sự kiện "ma" không
 * rút lại được.
 *
 * <p>Đổi lại là giao hàng <b>tối đa một lần</b>: RabbitMQ sập đúng lúc đó thì trạng thái
 * đã lưu nhưng sự kiện mất (có log ERROR để tra soát). Chấp nhận ở giai đoạn này, cùng
 * mức với phần còn lại của event bus (retry/DLQ để sau — docs/event-bus.md §7). Muốn
 * chắc chắn giao được thì nâng lên transactional outbox.
 *
 * <p>Lỗi phát sự kiện bị nuốt (chỉ log) có chủ đích: đến đây transaction đã commit, ném
 * lỗi ra chỉ khiến Admin nhận 500 cho một thao tác thật ra đã thành công.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShopEventPublisher {

	private final EventPublisher eventPublisher;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onStatusChanged(ShopStatusChangedEvent event) {
		switch (event.action()) {
			case APPROVE, REINSTATE -> publish(EventType.SHOP_APPROVED, event, new ShopApproved(
					event.shopId(), event.ownerId(), event.shopName(), event.actorId(),
					event.action() == ShopAction.REINSTATE));
			case SUSPEND -> publish(EventType.SHOP_SUSPENDED, event, new ShopSuspended(
					event.shopId(), event.ownerId(), event.shopName(), event.actorId(), event.reason()));
			case REJECT, RESUBMIT -> {
				// Không có sự kiện tương ứng trong SRS §8.1.
			}
		}
	}

	private void publish(String eventType, ShopStatusChangedEvent event, Object payload) {
		try {
			eventPublisher.publish(eventType, payload);
			log.info("Đã phát {} cho gian hàng id={}", eventType, event.shopId());
		} catch (RuntimeException ex) {
			log.error("KHÔNG phát được {} cho gian hàng id={} (hành động {} đã được lưu): {}",
					eventType, event.shopId(), event.action(), ex.getMessage(), ex);
		}
	}
}
