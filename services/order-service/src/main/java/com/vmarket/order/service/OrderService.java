package com.vmarket.order.service;

import com.github.f4b6a3.ulid.UlidCreator;
import com.vmarket.events.EventType;
import com.vmarket.events.OrderStatusChanged;
import com.vmarket.order.client.CartServiceClient;
import com.vmarket.order.client.CartServiceClient.CartIssueView;
import com.vmarket.order.client.CartServiceClient.CartItemView;
import com.vmarket.order.client.CartServiceClient.CartValidationView;
import com.vmarket.order.client.CartServiceClient.CartView;
import com.vmarket.order.client.CartServiceClient.ValidatedItemView;
import com.vmarket.order.client.UserServiceClient;
import com.vmarket.order.client.UserServiceClient.AddressView;
import com.vmarket.order.dto.OrderResponse;
import com.vmarket.order.dto.PlaceOrderRequest;
import com.vmarket.order.dto.PlaceOrderResponse;
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

import java.math.BigDecimal;
import java.util.Comparator;
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
 *   <li>Kiểm tra giỏ không rỗng (và phát hiện checkout song song cùng Idempotency-Key).
 *   <li>Gọi {@code POST /api/cart/validate}: cart-service đối chiếu giá/shop/tồn kho với
 *       product-service và trả kèm CHÍNH danh sách item đã kiểm tra. Chụp từng item trong danh sách
 *       đó thành {@link OrderItem} (không dùng snapshot {@code GET /api/cart} đọc trước đó — sửa giỏ
 *       xen giữa hai lần đọc không thể làm đơn lệch với thứ đã được kiểm tra).
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
    public PlaceOrderResponse placeOrder(
            String userId, String bearerToken, String idempotencyKey, PlaceOrderRequest request) {
        String trimmedKey =
                (idempotencyKey != null && !idempotencyKey.isBlank())
                        ? idempotencyKey.trim()
                        : null;

        // 1. Kiểm tra nhanh trước: nếu đã có đơn với key này thì trả lại ngay
        if (trimmedKey != null) {
            Optional<PlaceOrderResponse> existing = findIdempotentOrders(userId, trimmedKey);
            if (existing.isPresent()) {
                log.info(
                        "Idempotency-Key '{}' đã dùng → trả lại checkout {} cho userId={}",
                        trimmedKey,
                        existing.get().checkoutId(),
                        userId);
                return existing.get();
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
                Optional<PlaceOrderResponse> existing = findIdempotentOrders(userId, trimmedKey);
                if (existing.isPresent()) {
                    log.info(
                            "Idempotency-Key '{}' đã dùng (giỏ đã xoá bởi request song song) → trả lại checkout {}",
                            trimmedKey,
                            existing.get().checkoutId());
                    return existing.get();
                }
            }
            throw ApiException.badRequest("CART_EMPTY", "Giỏ hàng đang trống, không thể đặt hàng");
        }

        // 3. Kiểm tra lại giá/shop/tồn kho với product-service (qua cart-service) để chặn giá do
        // client tự đặt. Không tạo đơn nào nếu giỏ đã thay đổi.
        CartValidationView validation = cartServiceClient.validateCart(userId);
        if (!validation.ok()) {
            // Giỏ không còn hợp lệ có thể do CHÍNH checkout này đã hoàn tất ở request song song
            // cùng Idempotency-Key (request đầu đã tạo đơn và xoá giỏ → validate thấy CART_EMPTY).
            // Trả lại đơn đã tạo thay vì 409 CART_CHANGED.
            if (trimmedKey != null) {
                Optional<PlaceOrderResponse> existing = findIdempotentOrders(userId, trimmedKey);
                if (existing.isPresent()) {
                    log.info(
                            "Idempotency-Key '{}' đã dùng (validate giỏ thất bại vì request song song đã checkout xong) → trả lại checkout {}",
                            trimmedKey,
                            existing.get().checkoutId());
                    return existing.get();
                }
            }
            throw validationFailure(validation);
        }

        // Dựng đơn từ ĐÚNG các item mà cart-service vừa kiểm tra (không dùng snapshot GET cart):
        // sửa giỏ xen giữa hai lần đọc không thể làm đơn lệch giá/shop so với thứ đã validate.
        List<ValidatedItemView> orderItems = validation.items();
        if (orderItems.isEmpty()) {
            log.error(
                    "cart-service trả ok=true nhưng không kèm items cho userId={} → không dựng được đơn",
                    userId);
            throw ApiException.unavailable(
                    "CART_VALIDATION_UNAVAILABLE",
                    "Chưa kiểm tra được giỏ hàng, vui lòng thử lại sau");
        }

        // 4. Tách theo shop, tạo các đơn trong MỘT transaction độc lập (REQUIRES_NEW)
        String checkoutId = UlidCreator.getUlid().toString();
        PlaceOrderResponse response;
        try {
            List<OrderResponse> orders =
                    orderCreator.createOrdersInTransaction(
                            userId, address, orderItems, trimmedKey, request, checkoutId);
            response = PlaceOrderResponse.of(checkoutId, orders);
        } catch (DataIntegrityViolationException ex) {
            return recoverIdempotentOrdersOrThrow(userId, trimmedKey, ex);
        }

        // 5. Xoá giỏ SAU KHI transaction đã hoàn tất và kết nối DB đã được giải phóng (PBL6-52 review)
        removeCheckoutItemsFromCart(userId, checkoutId, orderItems);

        return response;
    }

    /** Lỗi trả cho client khi {@code validation.ok() == false} (và không có đơn idempotent nào). */
    private static ApiException validationFailure(CartValidationView validation) {
        List<CartIssueView> issues = validation.issues();
        for (CartIssueView issue : issues) {
            if ("SERVICE_UNAVAILABLE".equals(issue.type())) {
                return ApiException.unavailable(
                        "CART_VALIDATION_UNAVAILABLE",
                        "Chưa kiểm tra được giỏ hàng, vui lòng thử lại sau");
            }
        }
        return ApiException.conflict("CART_CHANGED", cartChangedMessage(issues));
    }

    private static String cartChangedMessage(List<CartIssueView> issues) {
        if (issues.isEmpty()) {
            return "Giỏ hàng đã thay đổi, vui lòng kiểm tra lại trước khi đặt hàng";
        }
        CartIssueView first = issues.get(0);
        String detail =
                first.message() != null && !first.message().isBlank()
                        ? first.message()
                        : switch (first.type() == null ? "" : first.type()) {
                            case "PRICE_CHANGED" -> "Giá sản phẩm đã thay đổi";
                            case "SHOP_CHANGED" -> "Gian hàng của sản phẩm đã thay đổi";
                            case "OUT_OF_STOCK" -> "Sản phẩm không đủ tồn kho";
                            case "NOT_FOUND" -> "Sản phẩm không còn tồn tại";
                            default -> "Sản phẩm trong giỏ không hợp lệ";
                        };
        String more = issues.size() > 1 ? " (và " + (issues.size() - 1) + " vấn đề khác)" : "";
        return "Giỏ hàng đã thay đổi: " + detail + more + ". Vui lòng kiểm tra lại trước khi đặt hàng";
    }

    /** Các đơn đã tạo bởi một Idempotency-Key, theo thứ tự tạo (ULID đơn điệu). */
    private Optional<PlaceOrderResponse> findIdempotentOrders(String userId, String key) {
        List<Order> existing = orderRepository.findAllByUserIdAndIdempotencyKey(userId, key);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        List<OrderResponse> orders =
                existing.stream()
                        .sorted(Comparator.comparing(Order::getId))
                        .map(OrderResponse::from)
                        .toList();
        return Optional.of(
                new PlaceOrderResponse(
                        orders.get(0).checkoutId(),
                        orders.stream()
                                .map(OrderResponse::totalAmount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add),
                        orders));
    }

    private PlaceOrderResponse recoverIdempotentOrdersOrThrow(
            String userId, String trimmedKey, DataIntegrityViolationException ex) {
        if (trimmedKey != null) {
            // Xung đột UNIQUE trên (user_id, idempotency_key, shop_id) do retry đồng thời.
            // Transaction thắng đã commit nên query ngay các đơn đã commit.
            Optional<PlaceOrderResponse> existing = findIdempotentOrders(userId, trimmedKey);
            if (existing.isPresent()) {
                log.info(
                        "Xung đột UNIQUE Idempotency-Key '{}' cho userId={} (concurrent retry) -> trả lại checkout đã commit {}",
                        trimmedKey,
                        userId,
                        existing.get().checkoutId());
                return existing.get();
            }

            // Thử lại 1 lần sau 10ms phòng trường hợp trễ đọc/replication
            try {
                Thread.sleep(10);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            existing = findIdempotentOrders(userId, trimmedKey);
            if (existing.isPresent()) {
                log.info(
                        "Xung đột UNIQUE Idempotency-Key '{}' cho userId={} (concurrent retry) sau retry -> trả lại checkout đã commit {}",
                        trimmedKey,
                        userId,
                        existing.get().checkoutId());
                return existing.get();
            }
        }

        // Không tìm thấy đơn theo idempotencyKey: lỗi ràng buộc không phải do retry hợp lệ
        // (NOT NULL, FK, dữ liệu bất thường...). Log và rethrow nguyên vẹn.
        log.error(
                "DataIntegrityViolationException không phải do concurrent retry Idempotency-Key cho userId={}: {}",
                userId,
                ex.getMessage(),
                ex);
        throw ex;
    }

    private void removeCheckoutItemsFromCart(
            String userId, String checkoutId, List<ValidatedItemView> snapshot) {
        int success = 0;
        int failed = 0;
        for (ValidatedItemView item : snapshot) {
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
                    "Đã xoá {}/{} item khỏi giỏ cho userId={} (checkout {})",
                    success,
                    snapshot.size(),
                    userId,
                    checkoutId);
        } else {
            log.warn(
                    "Xoá giỏ một phần cho userId={} (checkout {}): {} thành công, {} thất bại (người dùng có thể tự xoá hoặc đã thay đổi giỏ)",
                    userId,
                    checkoutId,
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
