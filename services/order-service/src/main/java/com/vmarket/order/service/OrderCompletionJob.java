package com.vmarket.order.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.vmarket.events.EventType;
import com.vmarket.events.OrderStatusChanged;
import com.vmarket.order.entity.Order;
import com.vmarket.order.entity.OrderStatus;
import com.vmarket.order.entity.OrderReturn;
import com.vmarket.order.entity.ReturnStatus;
import com.vmarket.order.event.Outbox;
import com.vmarket.order.repository.OrderRepository;
import com.vmarket.order.repository.OrderReturnRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Tự động chuyển đơn hàng "Đã giao" (DELIVERED) sang "Hoàn thành" (COMPLETED) sau 7 ngày
 * nếu không có khiếu nại / yêu cầu trả hàng đang mở (FR-ORD-02).
 * Tự động chuyển yêu cầu trả hàng quá 48 giờ chưa phản hồi sang Admin phân xử (FR-ORD-07).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class OrderCompletionJob {

	private static final Duration RETURN_WINDOW = Duration.ofDays(7);
	private static final Duration SELLER_RESPONSE_WINDOW = Duration.ofHours(48);
	private static final List<ReturnStatus> ACTIVE_RETURN_STATUSES = List.of(
			ReturnStatus.PENDING, ReturnStatus.APPROVED, ReturnStatus.DISPUTED);

	private final OrderRepository orderRepository;
	private final OrderReturnRepository returnRepository;
	private final Outbox outbox;
	private final Clock clock;
	private final TransactionTemplate transactionTemplate;

	@Scheduled(fixedDelayString = "${app.order.completion-delay:60000}")
	public void run() {
		completeDeliveredOrders();
		escalateOverdueReturns();
	}

	private void completeDeliveredOrders() {
		Instant cutoff = clock.instant().minus(RETURN_WINDOW);
		List<Order> deliveredOrders = orderRepository.findByStatusAndDeliveredAtLessThanEqual(
				OrderStatus.DELIVERED, cutoff);
		if (deliveredOrders.isEmpty()) {
			deliveredOrders = orderRepository.findByStatusAndUpdatedAtLessThanEqual(
					OrderStatus.DELIVERED, cutoff);
		}

		for (Order order : deliveredOrders) {
			try {
				completeIfEligible(order.getId());
			} catch (RuntimeException ex) {
				log.warn("Không thể tự động hoàn tất đơn {}: {}", order.getId(), ex.getMessage());
			}
		}
	}

	public void escalateOverdueReturns() {
		Instant cutoff = clock.instant().minus(SELLER_RESPONSE_WINDOW);
		List<OrderReturn> overdueReturns = returnRepository.findByStatusAndCreatedAtLessThanEqual(
				ReturnStatus.PENDING, cutoff);

		for (OrderReturn r : overdueReturns) {
			try {
				transactionTemplate.execute(status -> {
					OrderReturn locked = returnRepository.lock(r.getId()).orElse(null);
					if (locked != null && locked.getStatus() == ReturnStatus.PENDING
							&& !locked.getCreatedAt().isAfter(clock.instant().minus(SELLER_RESPONSE_WINDOW))) {
						locked.setStatus(ReturnStatus.DISPUTED);
						locked.setAdminNote("Quá hạn 48 giờ người bán không phản hồi, tự động chuyển Admin phân xử");
						returnRepository.save(locked);
						log.info("Yêu cầu trả hàng {} quá hạn 48 giờ phản hồi của Seller -> tự động chuyển DISPUTED cho Admin", locked.getId());
					}
					return null;
				});
			} catch (RuntimeException ex) {
				log.warn("Không thể tự động chuyển yêu cầu trả hàng quá hạn {}: {}", r.getId(), ex.getMessage());
			}
		}
	}

	public boolean completeIfEligible(String orderId) {
		return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
			Order order = orderRepository.lock(orderId).orElse(null);
			if (order == null || order.getStatus() != OrderStatus.DELIVERED) {
				return false;
			}

			boolean hasActiveReturn = returnRepository.existsByOrderIdAndStatusIn(orderId, ACTIVE_RETURN_STATUSES);
			if (hasActiveReturn) {
				log.debug("Đơn {} có yêu cầu trả hàng chưa giải quyết, tạm hoãn hoàn tất", orderId);
				return false;
			}

			order.setStatus(OrderStatus.COMPLETED);
			orderRepository.save(order);

			outbox.add(
					EventType.ORDER_STATUS_CHANGED,
					new OrderStatusChanged(
							orderId,
							"DELIVERED",
							"COMPLETED",
							order.getPaymentMethod()));

			log.info("Tự động hoàn tất đơn {} sau 7 ngày giao hàng thành công (DELIVERED -> COMPLETED)", orderId);
			return true;
		}));
	}
}
