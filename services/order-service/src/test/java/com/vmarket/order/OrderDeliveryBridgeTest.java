package com.vmarket.order;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.vmarket.events.DeliveryStatusChanged;
import com.vmarket.order.config.CartServiceProperties;
import com.vmarket.order.config.DeliveryIntegrationProperties;
import com.vmarket.order.delivery.OrderDeliveryBridge;
import com.vmarket.order.entity.*;
import com.vmarket.order.entity.Order;
import com.vmarket.order.repository.OrderRepository;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

@SpringBootTest(
        properties =
                "spring.datasource.url=jdbc:h2:mem:order-delivery;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class OrderDeliveryBridgeTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderRepository orders;
    @Autowired OrderDeliveryBridge bridge;
    @Autowired com.vmarket.order.service.OrderService orderService;
    @Autowired DeliveryIntegrationProperties key;
    @Autowired CartServiceProperties cart;
    private Order order;

    @BeforeEach
    void seed() {
        jdbc.execute(
                "alter table orders add column if not exists delivery_version bigint not null"
                        + " default 0");
        jdbc.update("delete from event_outbox");
        order = new Order();
        order.setUserId("buyer");
        order.setStatus(OrderStatus.PROCESSING);
        order.setTotalAmount(new BigDecimal("100000"));
        order.setRecipientName("Buyer");
        order.setPhone("0901234567");
        order.setProvince("Đà Nẵng");
        order.setDistrict("Hải Châu");
        order.setWard("Phường 1");
        order.setStreetAddress("1 đường giao");
        orders.saveAndFlush(order);
    }

    @Test
    void internalKeyGuardsSnapshotAndPaymentInventoryReadiness() throws Exception {
        mvc.perform(get("/internal/delivery/orders/{id}", order.getId()))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        get("/internal/delivery/orders/{id}", order.getId())
                                .header("X-Internal-Api-Key", cart.getInternalApiKey()))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        get("/internal/delivery/orders/{id}", order.getId())
                                .header("X-Internal-Api-Key", key.getInternalApiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("buyer"));
        mvc.perform(
                        post("/internal/delivery/orders/{id}/claim", order.getId())
                                .header("X-Internal-Api-Key", key.getInternalApiKey()))
                .andExpect(status().isConflict());
        order.setStockReserved(true);
        order.setStatus(OrderStatus.PENDING);
        orders.saveAndFlush(order);
        mvc.perform(
                        post("/internal/delivery/orders/{id}/claim", order.getId())
                                .header("X-Internal-Api-Key", key.getInternalApiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockReserved").value(true))
                .andExpect(jsonPath("$.status").value("PROCESSING"));
        mvc.perform(
                        post("/internal/delivery/orders/{id}/claim", order.getId())
                                .header("X-Internal-Api-Key", key.getInternalApiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
        assertThat(jdbc.queryForObject("select count(*) from event_outbox", Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(() -> orderService.cancelMyOrder("buyer", order.getId()))
                .isInstanceOf(com.vmarket.order.exception.ApiException.class)
                .extracting(ex -> ((com.vmarket.order.exception.ApiException) ex).getCode())
                .isEqualTo("ORDER_NOT_CANCELLABLE");
    }

    @Test
    void duplicateAndReorderedEventsCannotRegressDeliveryOrRequestRefundTwice() {
        bridge.apply(new DeliveryStatusChanged(order.getId(), "IN_TRANSIT", 10));
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.SHIPPED);
        bridge.apply(new DeliveryStatusChanged(order.getId(), "PICKED_UP", 9));
        bridge.apply(new DeliveryStatusChanged(order.getId(), "RETURNED", 12));
        bridge.apply(new DeliveryStatusChanged(order.getId(), "RETURNED", 12));
        bridge.apply(new DeliveryStatusChanged(order.getId(), "DELIVERED", 11));
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(jdbc.queryForObject("select count(*) from event_outbox", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForList("select payload from event_outbox", String.class))
                .anyMatch(p -> p.contains("CANCELLED"));
    }

    @Test
    void multiShopOrder_handoffClaimFails_whenNotAllShopsConfirmed() throws Exception {
        Order multiOrder = new Order();
        multiOrder.setUserId("buyer");
        multiOrder.setStatus(OrderStatus.PENDING);
        multiOrder.setStockReserved(true);
        multiOrder.setTotalAmount(new BigDecimal("200000"));
        multiOrder.setRecipientName("Buyer");
        multiOrder.setPhone("0901234567");
        multiOrder.setProvince("Đà Nẵng");
        multiOrder.setDistrict("Hải Châu");
        multiOrder.setWard("Phường 1");
        multiOrder.setStreetAddress("1 đường giao");

        OrderItem itemA = new OrderItem();
        itemA.setOrder(multiOrder);
        itemA.setShopId("SHOP_A");
        itemA.setProductId("P1");
        itemA.setQuantity(1);
        itemA.setUnitPrice(new BigDecimal("100000"));
        itemA.setLineTotal(new BigDecimal("100000"));
        itemA.setConfirmed(true);
        multiOrder.getItems().add(itemA);

        OrderItem itemB = new OrderItem();
        itemB.setOrder(multiOrder);
        itemB.setShopId("SHOP_B");
        itemB.setProductId("P2");
        itemB.setQuantity(1);
        itemB.setUnitPrice(new BigDecimal("100000"));
        itemB.setLineTotal(new BigDecimal("100000"));
        itemB.setConfirmed(false);
        multiOrder.getItems().add(itemB);

        orders.saveAndFlush(multiOrder);

        // Claim fails with 409 ORDER_NOT_CONFIRMED
        mvc.perform(
                        post("/internal/delivery/orders/{id}/claim", multiOrder.getId())
                                .header("X-Internal-Api-Key", key.getInternalApiKey()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_CONFIRMED"));

        assertThat(orders.findById(multiOrder.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING);

        // Shop B confirms
        itemB.setConfirmed(true);
        orders.saveAndFlush(multiOrder);

        // Claim succeeds -> PROCESSING
        mvc.perform(
                        post("/internal/delivery/orders/{id}/claim", multiOrder.getId())
                                .header("X-Internal-Api-Key", key.getInternalApiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        assertThat(orders.findById(multiOrder.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PROCESSING);
    }

    @Test
    void deliveryStatusDelivered_setsDeliveredAt() {
        bridge.apply(new DeliveryStatusChanged(order.getId(), "DELIVERED", 20));
        Order delivered = orders.findById(order.getId()).orElseThrow();
        assertThat(delivered.getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(delivered.getDeliveredAt()).isNotNull();
    }
}
