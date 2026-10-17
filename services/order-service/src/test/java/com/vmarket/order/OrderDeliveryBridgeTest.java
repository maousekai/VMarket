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
}
