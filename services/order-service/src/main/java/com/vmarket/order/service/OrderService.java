package com.vmarket.order.service;

import com.vmarket.events.EventType;
import com.vmarket.events.OrderStatusChanged;
import com.vmarket.order.client.CartServiceClient;
import com.vmarket.order.client.CartServiceClient.CartItemView;
import com.vmarket.order.client.CartServiceClient.CartView;
import com.vmarket.order.client.UserServiceClient;
import com.vmarket.order.client.UserServiceClient.AddressView;
import com.vmarket.order.dto.OrderResponse;
import com.vmarket.order.dto.PlaceOrderRequest;
import com.vmarket.order.entity.Order;
import com.vmarket.order.entity.OrderStatus;
import com.vmarket.order.exception.ApiException;
import com.vmarket.order.repository.OrderRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * FR-ORDER-01/02/03 — đặt hàng từ giỏ, xem đơn của tôi, huỷ đơn.
 *
 * <p><b>Luồng đặt hàng</b> (mỗi bước đều có mã lỗi riêng để client biết mình sai gì):
 *
 * <ol>
 *   <li>Kiểm tra Idempotency-Key: nếu đã có đơn với cùng key → trả lại đơn cũ, không tạo đơn mới
 *       (chống retry / gửi đồng thời).
 *   <li>Đọc địa chỉ từ user-service bằng token của người dùng — snapshot vào đơn. Endpoint {@code
 *       /me/addresses/{id}} đã kiểm tra ownership bằng JWT, nên nếu trả OK thì địa chỉ chắc chắn
 *       thuộc người đang gọi.
 *   <li>Đọc giỏ từ cart-service bằng {@code X-User-Id} đã xác thực.
 *   <li>Kiểm tra giỏ không rỗng.
 *   <li>Chụp từng item thành {@link OrderItem} với giá đã có trong giỏ (snapshot của cart-service,
 *       không hỏi lại product-service — giá chốt là giá đã hiện cho người dùng khi thêm vào giỏ).
 *   <li>Lưu đơn + item trong MỘT transaction. <b>Sau khi commit</b>, xoá từng item đã checkout khỏi
 *       giỏ (không xoá toàn bộ, tránh mất item thêm trong lúc checkout). Xoá hỏng chỉ log cảnh báo,
 *       không làm hỏng đơn đã tạo.
 * </ol>
 *
 * <p><b>Tổng tiền</b> được tính lại ở đây từ {@code unitPrice * quantity} thay vì tin {@code
 * lineTotal}/{@code totalAmount} của cart-service: đơn hàng là con số pháp lý nên phải tự nhân lại
 * từ hai thừa số gốc. Cộng theo {@code BigDecimal} hết, không đi qua {@code double}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {
    private final OrderRepository orderRepository;
    private final UserServiceClient userServiceClient;
    private final CartServiceClient cartServiceClient;
    private final com.vmarket.order.event.Outbox outbox;
    private final OrderCreator orderCreator;

    /**
     * Đặt hàng từ giỏ hiện có (FR-ORDER-01, PBL6-52).
     *
     * <p>Không giữ transaction cơ sở dữ liệu trong suốt thời gian gọi user-service và cart-service.
     * Transaction tạo đơn chỉ mở trong {@link OrderCreator#createOrderInTransaction}. Khi xảy ra
     * xung đột khoá duy nhất (UNIQUE constraint) do retry đồng thời cùng {@code Idempotency-Key},
     * phương thức này bắt {@link DataIntegrityViolationException} và truy vấn lại đơn đã được commit
     * bởi request song song để trả về thay vì làm sập request với lỗi 500.
     *
     * @param bearerToken header {@code Authorization} gốc, forward nguyên vẹn cho user-service khi
     *     đọc địa chỉ
     * @param idempotencyKey khoá chống trùng (header {@code Idempotency-Key}), có thể {@code null}
     *     nếu client không gửi
     */
    public OrderResponse placeOrder(
            String userId, String bearerToken, String idempotencyKey, PlaceOrderRequest request) {
        String trimmedKey =
                (idempotencyKey != null && !idempotencyKey.isBlank())
                        ? idempotencyKey.trim()
                        : null;

        // 1. Kiểm tra nhanh trước: nếu đã có đơn với key này thì trả lại ngay
        if (trimmedKey != null) {
            Optional<Order> existing =
                    orderRepository.findByUserIdAndIdempotencyKey(userId, trimmedKey);
            if (existing.isPresent()) {
                log.info(
                        "Idempotency-Key '{}' đã dùng → trả lại đơn {} cho userId={}",
                        trimmedKey,
                        existing.get().getId(),
                        userId);
                return OrderResponse.from(existing.get());
            }
        }

        // user-service endpoint /me/addresses/{id} đã kiểm tra ownership bằng JWT:
        // nếu trả OK thì địa chỉ chắc chắn thuộc người đang gọi, không cần so userId.
        AddressView address = userServiceClient.getAddress(bearerToken, request.addressId());

        CartView cart = cartServiceClient.getCart(userId);
        List<CartItemView> cartItems =
                cart.groups().stream().flatMap(group -> group.items().stream()).toList();

        // 2. Nếu giỏ rỗng: kiểm tra xem có phải do request song song cùng Idempotency-Key đã
        // hoàn tất checkout và xoá giỏ trước hay không (PBL6-52).
        if (cartItems.isEmpty()) {
            if (trimmedKey != null) {
                Optional<Order> existing =
                        orderRepository.findByUserIdAndIdempotencyKey(userId, trimmedKey);
                if (existing.isPresent()) {
                    log.info(
                            "Idempotency-Key '{}' đã dùng (giỏ đã xoá bởi request song song) → trả lại đơn {}",
                            trimmedKey,
                            existing.get().getId());
                    return OrderResponse.from(existing.get());
                }
            }
            throw ApiException.badRequest("CART_EMPTY", "Giỏ hàng đang trống, không thể đặt hàng");
        }

        // 3. Thực hiện tạo đơn trong transaction độc lập (REQUIRES_NEW)
        try {
            return orderCreator.createOrderInTransaction(
                    userId, address, cartItems, trimmedKey, request);
        } catch (DataIntegrityViolationException ex) {
            // Xung đột UNIQUE constraint (user_id, idempotency_key) do request song song commit trước
            if (trimmedKey != null) {
                log.info(
                        "Xung đột UNIQUE Idempotency-Key '{}' cho userId={} (concurrent retry). Nạp lại đơn đã commit...",
                        trimmedKey,
                        userId);
                // Do transaction thắng đã commit nên unique constraint mới xảy ra trên transaction này,
                // nạp ngay đơn đã commit mà không cần polling cố định lâu.
                Optional<Order> existing =
                        orderRepository.findByUserIdAndIdempotencyKey(userId, trimmedKey);
                if (existing.isPresent()) {
                    return OrderResponse.from(existing.get());
                }
                // Dự phòng 1 lần thử lại rất ngắn (10ms) phòng trường hợp trễ đọc bất thường
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                existing = orderRepository.findByUserIdAndIdempotencyKey(userId, trimmedKey);
                if (existing.isPresent()) {
                    return OrderResponse.from(existing.get());
                }
                log.warn(
                        "Recovery idempotency thất bại cho key '{}', userId={}",
                        trimmedKey,
                        userId);
            }
            throw ex;
        }
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
     * <p>Chỉ cho huỷ khi còn {@code PENDING}: đã sang {@code PROCESSING} nghĩa là người bán đang
     * chuẩn bị hàng, huỷ lúc đó phải qua luồng đồng thuận với người bán (ticket sau), không phải
     * quyền đơn phương của người mua.
     */
    @Transactional
    public OrderResponse cancelMyOrder(String userId, String orderId) {
        Order order =
                orderRepository
                        .lock(orderId)
                        .filter(o -> userId.equals(o.getUserId()))
                        .orElseThrow(
                                () ->
                                        ApiException.notFound(
                                                "ORDER_NOT_FOUND", "Không tìm thấy đơn hàng"));
        if (order.getStatus() != OrderStatus.PENDING
                && order.getStatus() != OrderStatus.WAITING_PAYMENT) {
            throw ApiException.conflict(
                    "ORDER_NOT_CANCELLABLE",
                    "Đơn hàng đang được xử lý, không thể huỷ. Vui lòng liên hệ người bán.");
        }
        var previous = order.getStatus();
        order.setStatus(OrderStatus.CANCELLED);
        outbox.add(
                EventType.ORDER_STATUS_CHANGED,
                new OrderStatusChanged(
                        orderId, previous.name(), "CANCELLED", order.getPaymentMethod()));
        log.info("Huỷ đơn {} bởi userId={}", orderId, userId);
        return OrderResponse.from(orderRepository.save(order));
    }

    private Order mustFindOwn(String userId, String orderId) {
        return orderRepository
                .findByIdAndUserId(orderId, userId)
                .orElseThrow(
                        () -> ApiException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng"));
    }
}
