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
import com.vmarket.order.entity.OrderItem;
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
		ShopView myShop = null;
		if (bearerToken != null && !bearerToken.isBlank()) {
			myShop = shopServiceClient.getMyShop(bearerToken);
		}
		if (myShop == null || myShop.id() == null) {
			throw ApiException.forbidden("NO_SHOP_FOUND", "Tài khoản chưa có gian hàng trên hệ thống");
		}
		// Chống IDOR: Nếu client gửi kèm requestedShopId, bắt buộc phải trùng khớp với shop thuộc sở hữu
		if (requestedShopId != null && !requestedShopId.isBlank()) {
			if (!myShop.id().equals(requestedShopId.trim())) {
				log.warn("Cảnh báo bảo mật: userId={} cố tình truy cập shopId={} không thuộc sở hữu (shop sở hữu: {})",
						userId, requestedShopId, myShop.id());
				throw ApiException.forbidden("FORBIDDEN_SHOP_ACCESS", "Bạn không có quyền quản lý gian hàng này");
			}
		}
		return myShop.id();
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
	 * Seller xác nhận đơn và chuẩn bị hàng (FR-ORD-04).
	 *
	 * <p>Hỗ trợ đơn hàng chứa sản phẩm của nhiều gian hàng: từng gian hàng xác nhận phần mặt hàng của mình.
	 * Khi tất cả gian hàng trong đơn đều đã xác nhận, toàn bộ đơn chuyển từ PENDING sang PROCESSING và phát
	 * sự kiện OrderStatusChanged(PROCESSING).
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

		boolean alreadyConfirmed = order.getItems().stream()
				.filter(i -> targetShopId.equals(i.getShopId()))
				.allMatch(OrderItem::isConfirmed);
		if (alreadyConfirmed) {
			throw ApiException.conflict("SHOP_ALREADY_CONFIRMED", "Gian hàng của bạn đã xác nhận đơn này rồi");
		}

		if (order.getStatus() == OrderStatus.WAITING_PAYMENT) {
			throw ApiException.conflict("ORDER_WAITING_PAYMENT", "Đơn hàng đang chờ thanh toán, người bán chưa thể xác nhận");
		}
		if (order.getStatus() != OrderStatus.PENDING && order.getStatus() != OrderStatus.PROCESSING) {
			throw ApiException.conflict("INVALID_ORDER_STATUS",
					"Chỉ có thể xác nhận đơn ở trạng thái PENDING. Trạng thái hiện tại: " + order.getStatus());
		}

		// Đánh dấu các mặt hàng của shop này đã được xác nhận
		order.getItems().stream()
				.filter(i -> targetShopId.equals(i.getShopId()))
				.forEach(i -> i.setConfirmed(true));

		// Nếu TẤT CẢ sản phẩm trong toàn bộ đơn hàng (của mọi shop) đều đã được xác nhận:
		boolean allConfirmed = order.getItems().stream().allMatch(OrderItem::isConfirmed);
		if (allConfirmed && order.getStatus() == OrderStatus.PENDING) {
			order.setStatus(OrderStatus.PROCESSING);
			outbox.add(
					EventType.ORDER_STATUS_CHANGED,
					new OrderStatusChanged(
							orderId,
							"PENDING",
							"PROCESSING",
							order.getPaymentMethod()));
			log.info("Tất cả gian hàng đã xác nhận đơn {} -> PROCESSING", orderId);
		} else {
			log.info("Gian hàng {} đã xác nhận đơn {} (chờ các gian hàng còn lại)", targetShopId, orderId);
		}

		Order saved = orderRepository.save(order);
		return SellerOrderResponse.from(saved, targetShopId);
	}
}
