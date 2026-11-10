package com.vmarket.order.service;

import com.vmarket.events.EventType;
import com.vmarket.events.OrderStatusChanged;
import com.vmarket.order.client.CartServiceClient;
import com.vmarket.order.client.CartServiceClient.CartItemView;
import com.vmarket.order.client.CartServiceClient.CartView;
import com.vmarket.order.client.UserServiceClient;
import com.vmarket.order.client.UserServiceClient.AddressView;
import com.vmarket.order.dto.CheckoutRequest;
import com.vmarket.order.dto.OrderResponse;
import com.vmarket.order.dto.PlaceOrderRequest;
import com.vmarket.order.entity.Order;
import com.vmarket.order.entity.OrderStatus;
import com.vmarket.order.entity.ReturnStatus;
import com.vmarket.order.exception.ApiException;
import com.vmarket.order.repository.OrderRepository;
import com.vmarket.order.repository.OrderReturnRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
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
    private final OrderReturnRepository returnRepository;
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
        OrderResponse response;
        try {
            response = orderCreator.createOrderInTransaction(
                    userId, address, cartItems, trimmedKey, request);
        } catch (DataIntegrityViolationException ex) {
            return recoverIdempotentOrderOrThrow(userId, trimmedKey, ex);
        }

        // 4. Xoá giỏ SAU KHI transaction đã hoàn tất và kết nối DB đã được giải phóng (PBL6-52 review)
        removeCheckoutItemsFromCart(userId, response.id(), cartItems);

        return response;
    }

    /**
     * Đặt hàng các dòng đã chọn trong giỏ, mỗi gian hàng một đơn (PBL6-29/30).
     *
     * <p>Mỗi đơn tạo trong transaction riêng ({@link OrderCreator}) với khoá chống trùng suy ra
     * từ {@code Idempotency-Key} + {@code shopId}: gửi lại cùng khoá thì đơn đã tạo được trả lại,
     * đơn còn thiếu (vd lần trước hỏng giữa chừng) mới được tạo. Dòng của đơn nào tạo xong thì
     * xoá khỏi giỏ ngay sau đó.
     */
    public List<OrderResponse> checkout(
            String userId, String bearerToken, String idempotencyKey, CheckoutRequest request) {
        String baseKey =
                (idempotencyKey != null && !idempotencyKey.isBlank()) ? idempotencyKey.trim() : null;
        AddressView address = userServiceClient.getAddress(bearerToken, request.addressId());
        CartView cart = cartServiceClient.getCart(userId);
        List<CartItemView> cartItems =
                cart.groups().stream().flatMap(group -> group.items().stream()).toList();

        List<CartItemView> chosen = cartItems;
        if (request.items() != null && !request.items().isEmpty()) {
            Set<String> wanted =
                    request.items().stream()
                            .map(item -> lineKey(item.productId(), item.variantId()))
                            .collect(Collectors.toSet());
            chosen =
                    cartItems.stream()
                            .filter(item -> wanted.contains(lineKey(item.productId(), item.variantId())))
                            .toList();
        }

        Map<String, List<CartItemView>> byShop = new LinkedHashMap<>();
        for (CartItemView item : chosen) {
            byShop.computeIfAbsent(item.shopId(), ignored -> new ArrayList<>()).add(item);
        }
        List<OrderResponse> orders = new ArrayList<>();
        if (byShop.isEmpty() && baseKey != null) {
            // Giỏ đã bị request song song cùng khoá xoá: trả lại các đơn đã tạo bằng khoá đó.
            orders.addAll(
                    orderRepository.findByUserIdAndIdempotencyKeyStartingWith(userId, baseKey + ":")
                            .stream()
                            .map(OrderResponse::from)
                            .toList());
        }
        if (byShop.isEmpty() && orders.isEmpty()) {
            throw ApiException.badRequest(
                    "CART_EMPTY", "Không có sản phẩm nào được chọn trong giỏ để đặt hàng");
        }

        for (Map.Entry<String, List<CartItemView>> group : byShop.entrySet()) {
            String key = shopKey(baseKey, group.getKey());
            Optional<Order> existing =
                    key == null ? Optional.empty() : orderRepository.findByUserIdAndIdempotencyKey(userId, key);
            OrderResponse response;
            if (existing.isPresent()) {
                response = OrderResponse.from(existing.get());
            } else {
                try {
                    response =
                            orderCreator.createOrderInTransaction(
                                    userId, address, group.getValue(), key, request.toPlaceOrder());
                } catch (DataIntegrityViolationException ex) {
                    response = recoverIdempotentOrderOrThrow(userId, key, ex);
                }
            }
            removeCheckoutItemsFromCart(userId, response.id(), group.getValue());
            orders.add(response);
        }
        return orders;
    }

    private static String lineKey(String productId, String variantId) {
        return productId + "::" + Objects.toString(variantId, "");
    }

    /** Khoá chống trùng của đơn thuộc {@code shopId}; vừa cột VARCHAR(64). */
    static String shopKey(String baseKey, String shopId) {
        if (baseKey == null) return null;
        String suffix = ":" + shopId;
        String prefix = baseKey.length() + suffix.length() > 64
                ? baseKey.substring(0, 64 - suffix.length())
                : baseKey;
        return prefix + suffix;
    }

    private OrderResponse recoverIdempotentOrderOrThrow(
            String userId, String trimmedKey, DataIntegrityViolationException ex) {
        if (trimmedKey != null) {
            // Trường hợp xung đột UNIQUE constraint trên (user_id, idempotency_key) do request retry đồng thời.
            // Do transaction thắng đã commit nên transaction này mới nhận unique violation, query ngay đơn đã commit.
            Optional<Order> existing =
                    orderRepository.findByUserIdAndIdempotencyKey(userId, trimmedKey);
            if (existing.isPresent()) {
                log.info(
                        "Xung đột UNIQUE Idempotency-Key '{}' cho userId={} (concurrent retry) -> trả lại đơn đã commit {}",
                        trimmedKey,
                        userId,
                        existing.get().getId());
                return OrderResponse.from(existing.get());
            }

            // Thử lại 1 lần sau 10ms phòng trường hợp trễ đọc/replication
            try {
                Thread.sleep(10);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            existing = orderRepository.findByUserIdAndIdempotencyKey(userId, trimmedKey);
            if (existing.isPresent()) {
                log.info(
                        "Xung đột UNIQUE Idempotency-Key '{}' cho userId={} (concurrent retry) sau retry -> trả lại đơn đã commit {}",
                        trimmedKey,
                        userId,
                        existing.get().getId());
                return OrderResponse.from(existing.get());
            }
        }

        // Nếu không tìm thấy đơn theo idempotencyKey, lỗi vi phạm ràng buộc không phải do retry hợp lệ
        // (ví dụ lỗi NOT NULL, Foreign Key, hoặc dữ liệu bất thường khác). Log cảnh báo cụ thể và rethrow nguyên vẹn.
        log.error(
                "DataIntegrityViolationException không phải do concurrent retry Idempotency-Key cho userId={}: {}",
                userId,
                ex.getMessage(),
                ex);
        throw ex;
    }

    private void removeCheckoutItemsFromCart(
            String userId, String orderId, List<CartItemView> snapshot) {
        int success = 0;
        int failed = 0;
        for (CartItemView item : snapshot) {
            boolean ok =
                    cartServiceClient.removeCartItem(userId, item.productId(), item.variantId());
            if (ok) {
                success++;
            } else {
                failed++;
            }
        }
        if (failed == 0) {
            log.info(
                    "Đã xoá {}/{} item khỏi giỏ cho userId={} (đơn {})",
                    success,
                    snapshot.size(),
                    userId,
                    orderId);
        } else {
            log.warn(
                    "Xoá giỏ một phần cho userId={} (đơn {}): {} thành công, {} thất bại (người dùng có thể tự xoá hoặc đã thay đổi giỏ)",
                    userId,
                    orderId,
                    success,
                    failed);
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

    /**
     * Người mua xác nhận đã nhận hàng và hoàn tất đơn sớm (FR-ORDER-02).
     */
    @Transactional
    public OrderResponse completeMyOrder(String userId, String orderId) {
        Order order =
                orderRepository
                        .lock(orderId)
                        .filter(o -> userId.equals(o.getUserId()))
                        .orElseThrow(
                                () ->
                                        ApiException.notFound(
                                                "ORDER_NOT_FOUND", "Không tìm thấy đơn hàng"));
        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw ApiException.conflict(
                    "ORDER_NOT_COMPLETABLE",
                    "Chỉ có thể hoàn tất đơn khi đã nhận hàng (DELIVERED). Trạng thái hiện tại: "
                            + order.getStatus());
        }
        if (returnRepository.existsByOrderIdAndStatusIn(
                orderId,
                List.of(
                        ReturnStatus.PENDING,
                        ReturnStatus.APPROVED,
                        ReturnStatus.DISPUTED))) {
            throw ApiException.conflict(
                    "ORDER_HAS_ACTIVE_RETURN",
                    "Đơn hàng đang có yêu cầu trả hàng chưa giải quyết, không thể hoàn tất đơn.");
        }
        order.setStatus(OrderStatus.COMPLETED);
        outbox.add(
                EventType.ORDER_STATUS_CHANGED,
                new OrderStatusChanged(
                        orderId, "DELIVERED", "COMPLETED", order.getPaymentMethod()));
        log.info("Người mua userId={} xác nhận hoàn tất đơn {}", userId, orderId);
        return OrderResponse.from(orderRepository.save(order));
    }

    private Order mustFindOwn(String userId, String orderId) {
        return orderRepository
                .findByIdAndUserId(orderId, userId)
                .orElseThrow(
                        () -> ApiException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng"));
    }
}
