package com.vmarket.order.delivery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import com.vmarket.order.config.CartServiceProperties;
import com.vmarket.order.dto.OrderResponse;
import com.vmarket.order.entity.Order;
import com.vmarket.order.entity.OrderStatus;
import com.vmarket.order.exception.ApiException;
import com.vmarket.order.repository.OrderRepository;
import com.vmarket.events.EventPublisher;
import com.vmarket.events.EventType;
import com.vmarket.events.DeliveryStatusChanged;
import com.vmarket.events.OrderStatusChanged;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class OrderDeliveryBridge {
    private final OrderRepository orders;
    private final CartServiceProperties properties;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public void authorize(String key) {
        if (key == null || !MessageDigest.isEqual(properties.getInternalApiKey().getBytes(StandardCharsets.UTF_8),
                key.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException("UNAUTHORIZED", HttpStatus.UNAUTHORIZED, "Sai khoá nội bộ");
        }
    }

    @Transactional
    public JsonNode snapshot(String id, boolean claim) {
        // SQL row lock also serializes buyer cancellation against handoff.
        var rows = jdbc.queryForList("select id from orders where id = ? for update", String.class, id);
        if (rows.isEmpty()) throw ApiException.notFound("ORDER_NOT_FOUND", "Không có đơn hàng");
        Order order = orders.findById(id).orElseThrow();
        JsonNode response = internalSnapshot(order);
        if (claim) {
            if (order.getStatus() != OrderStatus.PENDING && order.getStatus() != OrderStatus.PROCESSING) {
                throw ApiException.conflict("ORDER_NOT_READY", "Đơn hàng chưa sẵn sàng bàn giao");
            }
            // Fulfillment depends on PBL6-19's authoritative inventory/payment state.
            if (!response.has("stockReserved")) {
                throw ApiException.unavailable("PAYMENT_INTEGRATION_REQUIRED", "Cần tích hợp PBL6-19 trước khi bàn giao");
            }
            // Never dispatch before the inventory reservation succeeds.
            if (response.has("stockReserved") && !response.path("stockReserved").asBoolean()) {
                throw ApiException.conflict("STOCK_NOT_RESERVED", "Chưa giữ được tồn kho");
            }
            if (order.getStatus() == OrderStatus.PENDING) {
                order.setStatus(OrderStatus.PROCESSING);
                enqueue(new OrderStatusChanged(id, "PENDING", "PROCESSING", response.path("paymentMethod").asString("COD")));
            }
        }
        return internalSnapshot(order);
    }

    @Transactional
    public void apply(DeliveryStatusChanged event) {
        if (event.version() <= 0) throw ApiException.badRequest("INVALID_VERSION", "Sai version");
        var versions = jdbc.queryForList("select delivery_version from orders where id = ? for update", Long.class, event.orderId());
        if (versions.isEmpty() || versions.get(0) >= event.version()) return;
        Order order = orders.findById(event.orderId()).orElseThrow();
        OrderStatus next = switch (event.status()) {
            case "PICKED_UP", "IN_TRANSIT", "FAILED", "RETURNING" -> OrderStatus.SHIPPED;
            case "DELIVERED" -> OrderStatus.DELIVERED;
            case "RETURNED" -> OrderStatus.CANCELLED;
            default -> null;
        };
        if (next == null) return;
        if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.DELIVERED) return;
        var before = order.getStatus();
        order.setStatus(next);
        jdbc.update("update orders set delivery_version = ? where id = ?", event.version(), event.orderId());
        if (before != next) {
            var snapshot = internalSnapshot(order);
            enqueue(new OrderStatusChanged(order.getId(), before.name(), next.name(), snapshot.path("paymentMethod").asString("COD")));
        }
    }

    private JsonNode internalSnapshot(Order order) {
        var node = (tools.jackson.databind.node.ObjectNode) json.valueToTree(OrderResponse.from(order));
        node.put("userId", order.getUserId());
        var properties = new org.springframework.beans.BeanWrapperImpl(order);
        if (properties.isReadableProperty("stockReserved")) {
            node.put("stockReserved", Boolean.TRUE.equals(properties.getPropertyValue("stockReserved")));
        }
        node.put("paymentMethod", properties.isReadableProperty("paymentMethod")
                ? String.valueOf(properties.getPropertyValue("paymentMethod")) : "COD");
        return node;
    }

    private void enqueue(OrderStatusChanged payload) {
        jdbc.update("insert into delivery_order_outbox values (?, ?, ?, CURRENT_TIMESTAMP)", UUID.randomUUID().toString(),
                EventType.ORDER_STATUS_CHANGED, json.writeValueAsString(payload));
    }
}
