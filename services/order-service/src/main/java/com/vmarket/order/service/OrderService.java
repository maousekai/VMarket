package com.vmarket.order.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.order.client.CartServiceClient;
import com.vmarket.order.client.CartServiceClient.CartItemView;
import com.vmarket.order.client.CartServiceClient.CartView;
import com.vmarket.order.client.UserServiceClient;
import com.vmarket.order.client.UserServiceClient.AddressView;
import com.vmarket.order.dto.OrderResponse;
import com.vmarket.order.dto.PlaceOrderRequest;
import com.vmarket.order.entity.Order;
import com.vmarket.order.entity.OrderItem;
import com.vmarket.order.entity.OrderStatus;
import com.vmarket.order.exception.ApiException;
import com.vmarket.order.repository.OrderRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * FR-ORDER-01/02/03 — đặt hàng từ giỏ, xem đơn của tôi, huỷ đơn.
 *
 * <p><b>Luồng đặt hàng</b> (mỗi bước đều có mã lỗi riêng để client biết mình sai gì):
 * <ol>
 *   <li>Đọc địa chỉ từ user-service bằng token của người dùng — snapshot vào đơn,
 *       so khớp {@code userId} chặn IDOR phòng hai service lệch nhau.</li>
 *   <li>Đọc giỏ từ cart-service bằng {@code X-User-Id} đã xác thực.</li>
 *   <li>Kiểm tra giỏ không rỗng.</li>
 *   <li>Chụp từng item thành {@link OrderItem} với giá đã có trong giỏ (snapshot
 *       của cart-service, không hỏi lại product-service — giá chốt là giá đã hiện
 *       cho người dùng khi thêm vào giỏ).</li>
 *   <li>Lưu đơn + item trong MỘT transaction, sau đó xoá giỏ — xoá hỏng chỉ log
 *       cảnh báo, không làm hỏng đơn đã tạo.</li>
 * </ol>
 *
 * <p><b>Tổng tiền</b> được tính lại ở đây từ {@code unitPrice * quantity} thay vì
 * tin {@code lineTotal}/{@code totalAmount} của cart-service: đơn hàng là con số
 * pháp lý nên phải tự nhân lại từ hai thừa số gốc. Cộng theo {@code BigDecimal}
 * hết, không đi qua {@code double}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

	private final OrderRepository orderRepository;
	private final UserServiceClient userServiceClient;
	private final CartServiceClient cartServiceClient;

	/**
	 * Đặt hàng từ giỏ hiện có (FR-ORDER-01).
	 *
	 * @param bearerToken header {@code Authorization} gốc, forward nguyên vẹn cho
	 *                    user-service khi đọc địa chỉ
	 */
	@Transactional
	public OrderResponse placeOrder(String userId, String bearerToken, PlaceOrderRequest request) {
		AddressView address = userServiceClient.getAddress(bearerToken, request.addressId());
		if (!userId.equals(address.userId())) {
			// Phòng thủ thứ hai sau IDOR của user-service: nếu địa chỉ nạp được mà
			// không thuộc người đang đặt thì cấu hình/lỗi đồng bộ ở đâu đó — không
			// bao giờ ghi đơn với địa chỉ của người khác.
			log.error("Địa chỉ {} không thuộc userId={} — từ chối đặt hàng", request.addressId(), userId);
			throw ApiException.notFound("ADDRESS_NOT_FOUND", "Không tìm thấy địa chỉ giao hàng");
		}

		CartView cart = cartServiceClient.getCart(userId);
		List<CartItemView> cartItems = cart.groups().stream()
				.flatMap(group -> group.items().stream())
				.toList();
		if (cartItems.isEmpty()) {
			throw ApiException.badRequest("CART_EMPTY", "Giỏ hàng đang trống, không thể đặt hàng");
		}

		return persistOrder(userId, address, cartItems, request);
	}

	/** Chụp giỏ + địa chỉ thành đơn và lưu trong một transaction. */
	private OrderResponse persistOrder(String userId, AddressView address,
			List<CartItemView> cartItems, PlaceOrderRequest request) {
		Order order = new Order();
		order.setUserId(userId);
		order.setStatus(OrderStatus.PENDING);
		order.setRecipientName(address.recipientName());
		order.setPhone(address.phone());
		order.setProvince(address.province());
		order.setDistrict(address.district());
		order.setWard(address.ward());
		order.setStreetAddress(address.streetAddress());
		order.setNote(request.note() == null || request.note().isBlank() ? null : request.note().trim());

		BigDecimal total = BigDecimal.ZERO;
		for (CartItemView cartItem : cartItems) {
			OrderItem item = new OrderItem();
			item.setOrder(order);
			item.setProductId(cartItem.productId());
			item.setVariantId(cartItem.variantId());
			item.setShopId(cartItem.shopId());
			item.setQuantity(cartItem.quantity());
			item.setUnitPrice(cartItem.unitPrice());
			item.setLineTotal(cartItem.unitPrice().multiply(BigDecimal.valueOf(cartItem.quantity())));
			order.getItems().add(item);
			total = total.add(item.getLineTotal());
		}
		order.setTotalAmount(total);

		// saveAndFlush để INSERT chạy ngay trong transaction, @CreationTimestamp gán
		// createdAt trước khi build response — nếu chỉ save() thì id được gán nhưng
		// createdAt vẫn null cho tới lúc commit (response trả về trước đó sẽ thiếu).
		Order saved = orderRepository.saveAndFlush(order);
		log.info("Tạo đơn {} cho userId={}, {} item, tổng {}",
				saved.getId(), userId, order.getItems().size(), total);

		// Xoá giỏ SAU khi đơn đã commit. Thất bại ở đây không được phép hủy đơn:
		// người dùng xoá tay vài item còn lại, hoặc giỏ tự hết hạn sau 30 ngày.
		if (cartServiceClient.clearCart(userId)) {
			log.info("Đã xoá giỏ của userId={} sau khi tạo đơn {}", userId, saved.getId());
		} else {
			log.warn("Không xoá được giỏ của userId={} sau khi tạo đơn {} — giỏ còn nguyên, "
					+ "người dùng có thể đặt lại hoặc tự xoá item", userId, saved.getId());
		}

		return OrderResponse.from(saved);
	}

	/** Danh sách đơn của người đang đăng nhập, mới nhất trước (FR-ORDER-02). */
	@Transactional(readOnly = true)
	public List<OrderResponse> listMyOrders(String userId) {
		return orderRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
				.map(OrderResponse::from)
				.toList();
	}

	/** Chi tiết một đơn — chỉ trong phạm vi của chính người dùng (FR-ORDER-02). */
	@Transactional(readOnly = true)
	public OrderResponse getMyOrder(String userId, String orderId) {
		return OrderResponse.from(mustFindOwn(userId, orderId));
	}

	/**
	 * Huỷ đơn do người mua tự huỷ (FR-ORDER-03).
	 *
	 * <p>Chỉ cho huỷ khi còn {@code PENDING}: đã sang {@code PROCESSING} nghĩa là
	 * người bán đang chuẩn bị hàng, huỷ lúc đó phải qua luồng đồng thuận với người
	 * bán (ticket sau), không phải quyền đơn phương của người mua.
	 */
	@Transactional
	public OrderResponse cancelMyOrder(String userId, String orderId) {
		Order order = mustFindOwn(userId, orderId);
		if (order.getStatus() != OrderStatus.PENDING) {
			throw ApiException.conflict("ORDER_NOT_CANCELLABLE",
					"Đơn hàng đang được xử lý, không thể huỷ. Vui lòng liên hệ người bán.");
		}
		order.setStatus(OrderStatus.CANCELLED);
		log.info("Huỷ đơn {} bởi userId={}", orderId, userId);
		return OrderResponse.from(orderRepository.save(order));
	}

	private Order mustFindOwn(String userId, String orderId) {
		return orderRepository.findByIdAndUserId(orderId, userId)
				.orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng"));
	}
}