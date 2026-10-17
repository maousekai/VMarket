package com.vmarket.order.delivery;

import com.vmarket.events.DeliveryStatusChanged;
import com.vmarket.events.EventType;
import com.vmarket.events.OrderStatusChanged;
import com.vmarket.order.config.DeliveryIntegrationProperties;
import com.vmarket.order.dto.OrderResponse;
import com.vmarket.order.entity.Order;
import com.vmarket.order.entity.OrderStatus;
import com.vmarket.order.event.Outbox;
import com.vmarket.order.exception.ApiException;
import com.vmarket.order.repository.OrderRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
@RequiredArgsConstructor
public class OrderDeliveryBridge {
    private final OrderRepository orders;
    private final DeliveryIntegrationProperties properties;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Outbox outbox;

    public void authorize(String key) {
        if (key == null
                || !MessageDigest.isEqual(
                        properties.getInternalApiKey().getBytes(StandardCharsets.UTF_8),
                        key.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException("UNAUTHORIZED", HttpStatus.UNAUTHORIZED, "Sai khoá nội bộ");
        }
    }

    @Transactional
    public JsonNode snapshot(String id, boolean claim) {
        // SQL row lock also serializes buyer cancellation against handoff.
        var rows =
                jdbc.queryForList(
                        "select id from orders where id = ? for update", String.class, id);
        if (rows.isEmpty()) throw ApiException.notFound("ORDER_NOT_FOUND", "Không có đơn hàng");
        Order order = orders.findById(id).orElseThrow();
        JsonNode response = internalSnapshot(order);
        if (claim) {
            if (order.getStatus() != OrderStatus.PENDING
                    && order.getStatus() != OrderStatus.PROCESSING) {
                throw ApiException.conflict("ORDER_NOT_READY", "Đơn hàng chưa sẵn sàng bàn giao");
            }
            // Never dispatch before the inventory reservation succeeds.
            if (!order.isStockReserved()) {
                throw ApiException.conflict("STOCK_NOT_RESERVED", "Chưa giữ được tồn kho");
            }
            if (order.getStatus() == OrderStatus.PENDING) {
                order.setStatus(OrderStatus.PROCESSING);
                enqueue(
                        new OrderStatusChanged(
                                id,
                                "PENDING",
                                "PROCESSING",
                                response.path("paymentMethod").asString("COD")));
            }
        }
        return internalSnapshot(order);
    }

    @Transactional
    public void apply(DeliveryStatusChanged event) {
        if (event.version() <= 0) throw ApiException.badRequest("INVALID_VERSION", "Sai version");
        var versions =
                jdbc.queryForList(
                        "select delivery_version from orders where id = ? for update",
                        Long.class,
                        event.orderId());
        if (versions.isEmpty() || versions.get(0) >= event.version()) return;
        Order order = orders.findById(event.orderId()).orElseThrow();
        OrderStatus next =
                switch (event.status()) {
                    case "PICKED_UP", "IN_TRANSIT", "FAILED", "RETURNING" -> OrderStatus.SHIPPED;
                    case "DELIVERED" -> OrderStatus.DELIVERED;
                    case "RETURNED" -> OrderStatus.CANCELLED;
                    default -> null;
                };
        if (next == null) return;
        if (order.getStatus() == OrderStatus.CANCELLED
                || order.getStatus() == OrderStatus.DELIVERED) return;
        var before = order.getStatus();
        order.setStatus(next);
        jdbc.update(
                "update orders set delivery_version = ? where id = ?",
                event.version(),
                event.orderId());
        if (before != next) {
            var snapshot = internalSnapshot(order);
            enqueue(
                    new OrderStatusChanged(
                            order.getId(),
                            before.name(),
                            next.name(),
                            snapshot.path("paymentMethod").asString("COD")));
        }
    }

    private JsonNode internalSnapshot(Order order) {
        var node = (ObjectNode) json.valueToTree(OrderResponse.from(order));
        node.put("userId", order.getUserId());
        node.put("stockReserved", order.isStockReserved());
        node.put("paymentMethod", order.getPaymentMethod());
        return node;
    }

    private void enqueue(OrderStatusChanged payload) {
        outbox.add(EventType.ORDER_STATUS_CHANGED, payload);
    }
}
