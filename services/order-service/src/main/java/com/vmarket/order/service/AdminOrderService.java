package com.vmarket.order.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.events.EventType;
import com.vmarket.events.OrderStatusChanged;
import com.vmarket.order.dto.OrderResponse;
import com.vmarket.order.entity.Order;
import com.vmarket.order.entity.OrderStatus;
import com.vmarket.order.event.Outbox;
import com.vmarket.order.exception.ApiException;
import com.vmarket.order.repository.OrderRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Quản lý và giám sát đơn hàng toàn sàn dành cho Admin (FR-ORD-05).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOrderService {

	private final OrderRepository orderRepository;
	private final Outbox outbox;

	@Transactional(readOnly = true)
	public Page<OrderResponse> listOrders(OrderStatus status, String userId, int page, int size) {
		Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
		Page<Order> orders;

		if (userId != null && !userId.isBlank() && status != null) {
			orders = orderRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId.trim(), status, pageable);
		} else if (userId != null && !userId.isBlank()) {
			orders = orderRepository.findByUserIdOrderByCreatedAtDesc(userId.trim(), pageable);
		} else if (status != null) {
			orders = orderRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
		} else {
			orders = orderRepository.findAllByOrderByCreatedAtDesc(pageable);
		}

		return orders.map(OrderResponse::from);
	}

	@Transactional(readOnly = true)
	public OrderResponse getOrder(String orderId) {
		Order order = orderRepository.findById(orderId)
				.orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng"));
		return OrderResponse.from(order);
	}

	/**
	 * Admin can thiệp huỷ đơn hàng khi có tranh chấp/khiếu nại (FR-ORD-05).
	 */
	@Transactional
	public OrderResponse cancelOrder(String adminId, String orderId, String reason) {
		Order order = orderRepository.lock(orderId)
				.orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng"));

		if (order.getStatus() == OrderStatus.CANCELLED) {
			throw ApiException.conflict("ORDER_ALREADY_CANCELLED", "Đơn hàng đã bị huỷ trước đó");
		}
		if (order.getStatus() == OrderStatus.COMPLETED) {
			throw ApiException.conflict("ORDER_ALREADY_COMPLETED", "Đơn hàng đã hoàn tất, không thể huỷ");
		}

		OrderStatus previous = order.getStatus();
		order.setStatus(OrderStatus.CANCELLED);
		Order saved = orderRepository.save(order);

		outbox.add(
				EventType.ORDER_STATUS_CHANGED,
				new OrderStatusChanged(
						orderId,
						previous.name(),
						"CANCELLED",
						order.getPaymentMethod()));

		log.info("Admin {} huỷ đơn {} (trước đó: {}). Lý do: {}", adminId, orderId, previous, reason);
		return OrderResponse.from(saved);
	}
}
