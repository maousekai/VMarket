package com.vmarket.order.service;

import java.util.Objects;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.events.EventType;
import com.vmarket.events.OrderStatusChanged;
import com.vmarket.order.client.ShopServiceClient;
import com.vmarket.order.client.ShopServiceClient.ShopView;
import com.vmarket.order.dto.SellerOrderResponse;
import com.vmarket.order.entity.Order;
import com.vmarket.order.entity.OrderStatus;
import com.vmarket.order.event.Outbox;
import com.vmarket.order.exception.ApiException;
import com.vmarket.order.repository.OrderRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Xử lý đơn hàng cho Seller (FR-ORD-04).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SellerOrderService {

	private final OrderRepository orderRepository;
	private final ShopServiceClient shopServiceClient;
	private final Outbox outbox;

	public String resolveShopId(String userId, String bearerToken, String requestedShopId) {
		if (requestedShopId != null && !requestedShopId.isBlank()) {
			return requestedShopId.trim();
		}
		if (bearerToken != null && !bearerToken.isBlank()) {
			ShopView shop = shopServiceClient.getMyShop(bearerToken);
			if (shop != null && shop.id() != null) {
				return shop.id();
			}
		}
		throw ApiException.badRequest("SHOP_ID_REQUIRED", "Cần cung cấp shopId hoặc tài khoản chưa mở gian hàng");
	}

	@Transactional(readOnly = true)
	public Page<SellerOrderResponse> listSellerOrders(
			String userId, String bearerToken, String shopId, OrderStatus status, int page, int size) {
		String targetShopId = resolveShopId(userId, bearerToken, shopId);
		Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));

		Page<Order> orderPage = (status != null)
				? orderRepository.findByShopIdAndStatus(targetShopId, status, pageable)
				: orderRepository.findByShopId(targetShopId, pageable);

		return orderPage.map(order -> SellerOrderResponse.from(order, targetShopId));
	}

	@Transactional(readOnly = true)
	public SellerOrderResponse getSellerOrder(
			String userId, String bearerToken, String shopId, String orderId) {
		String targetShopId = resolveShopId(userId, bearerToken, shopId);
		Order order = orderRepository.findByIdAndShopId(orderId, targetShopId)
				.orElseThrow(() -> ApiException.notFound(
						"ORDER_NOT_FOUND", "Không tìm thấy đơn hàng hoặc đơn không chứa sản phẩm của gian hàng"));
		return SellerOrderResponse.from(order, targetShopId);
	}

	/**
	 * Seller xác nhận đơn và bắt đầu chuẩn bị hàng: PENDING -> PROCESSING (FR-ORD-04).
	 */
	@Transactional
	public SellerOrderResponse confirmOrder(
			String userId, String bearerToken, String shopId, String orderId) {
		String targetShopId = resolveShopId(userId, bearerToken, shopId);
		Order order = orderRepository.lock(orderId)
				.orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng"));

		boolean hasShopItem = order.getItems().stream().anyMatch(i -> targetShopId.equals(i.getShopId()));
		if (!hasShopItem) {
			throw ApiException.forbidden("NOT_SHOP_ORDER", "Đơn hàng không chứa sản phẩm của gian hàng này");
		}

		if (order.getStatus() == OrderStatus.WAITING_PAYMENT) {
			throw ApiException.conflict("ORDER_WAITING_PAYMENT", "Đơn hàng đang chờ thanh toán, người bán chưa thể xác nhận");
		}
		if (order.getStatus() != OrderStatus.PENDING) {
			throw ApiException.conflict("INVALID_ORDER_STATUS",
					"Chỉ có thể xác nhận đơn ở trạng thái PENDING. Trạng thái hiện tại: " + order.getStatus());
		}

		order.setStatus(OrderStatus.PROCESSING);
		Order saved = orderRepository.save(order);

		outbox.add(
				EventType.ORDER_STATUS_CHANGED,
				new OrderStatusChanged(
						orderId,
						"PENDING",
						"PROCESSING",
						order.getPaymentMethod()));

		log.info("Seller shopId={} đã xác nhận đơn {} (PENDING -> PROCESSING)", targetShopId, orderId);
		return SellerOrderResponse.from(saved, targetShopId);
	}
}
