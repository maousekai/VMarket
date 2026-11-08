package com.vmarket.order.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.github.f4b6a3.ulid.UlidCreator;
import com.vmarket.events.EventType;
import com.vmarket.events.OrderPlaced;
import com.vmarket.events.StockItem;
import com.vmarket.order.client.CartServiceClient.ValidatedItemView;
import com.vmarket.order.client.UserServiceClient.AddressView;
import com.vmarket.order.config.OrderPaymentProperties;
import com.vmarket.order.dto.OrderResponse;
import com.vmarket.order.dto.PlaceOrderRequest;
import com.vmarket.order.entity.Order;
import com.vmarket.order.entity.OrderItem;
import com.vmarket.order.entity.OrderStatus;
import com.vmarket.order.event.Outbox;
import com.vmarket.order.repository.OrderRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Thực hiện tạo đơn hàng trong transaction độc lập (REQUIRES_NEW).
 *
 * <p>PBL6-52: Tách logic tạo đơn khỏi {@link OrderService#placeOrder} để transaction
 * cơ sở dữ liệu không bị giữ trong suốt thời gian gọi downstream HTTP client (user-service,
 * cart-service), và quan trọng nhất: khi gặp xung đột khoá trùng lặp (UNIQUE constraint
 * violation trên {@code (user_id, idempotency_key, shop_id)}) do retry đồng thời, transaction này
 * rollback độc lập để caller có thể bắt exception và truy vấn lại đơn đã được tạo thành công
 * bởi request song song thay vì gây 500 INTERNAL_ERROR.
 *
 * <p>Lưu ý: Không thực hiện gọi HTTP (như xoá giỏ qua cart-service) trong transaction hay
 * trong transaction synchronization callback sau commit, nhằm giải phóng kết nối DB ngay
 * lập tức về pool trước khi thực hiện I/O mạng.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreator {

    private final Clock clock;
    private final OrderPaymentProperties paymentTiming;
    private final OrderRepository orderRepository;
    private final Outbox outbox;

    /**
     * Tách giỏ theo shop và tạo MỖI shop một đơn, tất cả trong cùng MỘT transaction REQUIRES_NEW:
     * hoặc đủ mọi đơn, hoặc không đơn nào. Các đơn chung {@code checkoutId}; thứ tự đơn theo thứ tự
     * nhóm shop xuất hiện trong giỏ (id đơn là ULID đơn điệu nên sắp theo id cũng ra đúng thứ tự).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OrderResponse> createOrdersInTransaction(
            String userId,
            AddressView address,
            List<ValidatedItemView> cartItems,
            String idempotencyKey,
            PlaceOrderRequest request,
            String checkoutId) {
        Map<String, List<ValidatedItemView>> byShop = new LinkedHashMap<>();
        for (ValidatedItemView cartItem : cartItems) {
            byShop.computeIfAbsent(cartItem.shopId(), k -> new ArrayList<>()).add(cartItem);
        }

        List<OrderResponse> responses = new ArrayList<>();
        for (Map.Entry<String, List<ValidatedItemView>> entry : byShop.entrySet()) {
            responses.add(
                    createSingleShopOrder(
                            userId,
                            address,
                            entry.getKey(),
                            entry.getValue(),
                            idempotencyKey,
                            request,
                            checkoutId));
        }
        return responses;
    }

    private OrderResponse createSingleShopOrder(
            String userId,
            AddressView address,
            String shopId,
            List<ValidatedItemView> cartItems,
            String idempotencyKey,
            PlaceOrderRequest request,
            String checkoutId) {
        Order order = new Order();
        // ULID đơn điệu: các đơn của cùng checkout sắp theo id đúng thứ tự tạo.
        order.setId(UlidCreator.getMonotonicUlid().toString());
        order.setUserId(userId);
        order.setShopId(shopId);
        order.setCheckoutId(checkoutId);
        order.setPaymentMethod(request.paymentMethod() == null ? "COD" : request.paymentMethod());
        order.setStatus(
                "PAYOS".equals(order.getPaymentMethod())
                        ? OrderStatus.WAITING_PAYMENT
                        : OrderStatus.PENDING);
        if ("PAYOS".equals(order.getPaymentMethod())) {
            order.setPaymentExpiresAt(clock.instant().plus(paymentTiming.getTtl()));
        }
        order.setRecipientName(address.recipientName());
        order.setPhone(address.phone());
        order.setProvince(address.province());
        order.setDistrict(address.district());
        order.setWard(address.ward());
        order.setStreetAddress(address.streetAddress());
        order.setNote(
                request.note() == null || request.note().isBlank() ? null : request.note().trim());

        // Gán idempotency key (nullable)
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            order.setIdempotencyKey(idempotencyKey.trim());
        }

        BigDecimal total = BigDecimal.ZERO;
        for (ValidatedItemView cartItem : cartItems) {
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProductId(cartItem.productId());
            item.setVariantId(cartItem.variantId());
            item.setShopId(cartItem.shopId());
            item.setQuantity(cartItem.quantity());
            item.setUnitPrice(cartItem.unitPrice());
            item.setLineTotal(
                    cartItem.unitPrice().multiply(BigDecimal.valueOf(cartItem.quantity())));
            order.getItems().add(item);
            total = total.add(item.getLineTotal());
        }
        order.setTotalAmount(total);

        // saveAndFlush để INSERT chạy ngay trong transaction, kiểm tra unique index
        Order saved = orderRepository.saveAndFlush(order);
        outbox.add(
                EventType.ORDER_PLACED,
                new OrderPlaced(
                        saved.getId(),
                        saved.getItems().stream()
                                .map(
                                        i ->
                                                new StockItem(
                                                        i.getProductId(),
                                                        i.getVariantId(),
                                                        i.getQuantity()))
                                .toList()));
        log.info(
                "Tạo đơn {} cho userId={}, {} item, tổng {}",
                saved.getId(),
                userId,
                order.getItems().size(),
                total);

        return OrderResponse.from(saved);
    }
}
