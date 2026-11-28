package com.vmarket.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.vmarket.events.EventConsumerDispatcher;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventPublisher;
import com.vmarket.events.EventType;
import com.vmarket.events.StockReservationFailed;
import com.vmarket.order.client.CartServiceClient.CartItemView;
import com.vmarket.order.client.UserServiceClient.AddressView;
import com.vmarket.order.dto.PlaceOrderRequest;
import com.vmarket.order.entity.OrderStatus;
import com.vmarket.order.event.OutboxDispatcher;
import com.vmarket.order.event.OutboxJob;
import com.vmarket.order.event.OutboxRepository;
import com.vmarket.order.repository.OrderRepository;
import com.vmarket.order.service.OrderCreator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * PBL6-62: order-service là nguồn phát {@code OrderPlaced}/{@code OrderStatusChanged} qua outbox
 * và phản ứng với {@code StockReservationFailed} của product-service.
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:order-stock-flow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.show-sql=false"
        })
class OrderStockEventFlowTest {

    private static final String ALICE = "01JBQ9USER000000000ALICE01";

    @Autowired OrderCreator creator;
    @Autowired OrderRepository orders;
    @Autowired OutboxRepository events;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired EventConsumerDispatcher consumers;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean EventPublisher publisher;

    @BeforeEach
    @AfterEach
    void clean() {
        events.deleteAll();
        orders.deleteAll();
    }

    private String checkout(String paymentMethod) {
        var address =
                new AddressView(
                        "01JBQ9ADDR0000000000TEST01",
                        "Nguyễn Văn An",
                        "0912345678",
                        "Đà Nẵng",
                        null,
                        "Hải Châu 1",
                        "1 Bạch Đằng");
        var item =
                new CartItemView(
                        "product-1",
                        "variant-1",
                        "shop-1",
                        2,
                        new BigDecimal("50000"),
                        new BigDecimal("100000"));
        return creator.createOrderInTransaction(
                        ALICE,
                        address,
                        List.of(item),
                        UUID.randomUUID().toString(),
                        new PlaceOrderRequest(address.id(), null, paymentMethod))
                .id();
    }

    @Test
    void checkoutDuringBrokerOutagePublishesOrderPlacedAfterRecovery() {
        doThrow(new AmqpConnectException(new ConnectException("Connection refused")))
                .when(publisher)
                .publish(anyString(), any());

        String orderId = checkout("COD");

        // Đơn đã commit cùng outbox row dù broker đang chết.
        assertThat(orders.findById(orderId)).isPresent();
        assertThat(events.findAll())
                .singleElement()
                .satisfies(
                        e -> {
                            assertThat(e.getEventType()).isEqualTo(EventType.ORDER_PLACED);
                            assertThat(e.getAggregateId()).isEqualTo(orderId);
                        });

        // Broker chưa lên: dispatcher thử, thất bại, GIỮ lại row để retry.
        dispatcher.dispatch();
        assertThat(events.findAll())
                .singleElement()
                .satisfies(
                        e -> {
                            assertThat(e.getState()).isEqualTo("PENDING");
                            assertThat(e.getRetryAttempts()).isEqualTo(1);
                        });

        // Broker phục hồi; tới lượt retry thì event được phát và row bị xoá.
        reset(publisher);
        doNothing().when(publisher).publish(anyString(), any());
        jdbc.update("update event_outbox set next_attempt_at=?", Timestamp.from(Instant.EPOCH));
        dispatcher.dispatch();

        var payload = ArgumentCaptor.forClass(Object.class);
        verify(publisher, times(1)).publish(eq(EventType.ORDER_PLACED), payload.capture());
        JsonNode published = (JsonNode) payload.getValue();
        assertThat(published.path("orderId").asString()).isEqualTo(orderId);
        assertThat(published.path("items")).hasSize(1);
        assertThat(published.path("items").get(0).path("productId").asString())
                .isEqualTo("product-1");
        assertThat(published.path("items").get(0).path("quantity").asInt()).isEqualTo(2);
        assertThat(events.count()).isZero();
    }

    private EventEnvelope stockFailure(String orderId) {
        return new EventEnvelope(
                UUID.randomUUID().toString(),
                EventType.STOCK_RESERVATION_FAILED,
                System.currentTimeMillis(),
                new StockReservationFailed(orderId, "INSUFFICIENT_STOCK", "Hết hàng"));
    }

    @ParameterizedTest
    @CsvSource({"COD, PENDING", "VNPAY, WAITING_PAYMENT"})
    void stockReservationFailureCancelsOrderAndAnnouncesCancellation(
            String paymentMethod, OrderStatus previousStatus) {
        String orderId = checkout(paymentMethod);
        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(previousStatus);
        events.deleteAll(); // bỏ OrderPlaced, chỉ quan tâm event sinh ra từ việc huỷ

        var failure = stockFailure(orderId);
        consumers.dispatch(failure);

        assertThat(orders.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(events.findAll())
                .singleElement()
                .satisfies(
                        e -> {
                            assertThat(e.getEventType())
                                    .isEqualTo(EventType.ORDER_STATUS_CHANGED);
                            assertThat(e.getPayload())
                                    .contains("\"previousStatus\":\"" + previousStatus.name() + "\"")
                                    .contains("\"status\":\"CANCELLED\"");
                        });

        // Khi nhận lại cùng message, stockFailed bỏ qua đơn vì trạng thái đã là CANCELLED.
        consumers.dispatch(failure);
        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void stockReservationFailureLeavesProcessingOrderUnchanged() {
        String orderId = checkout("COD");
        jdbc.update("update orders set status='PROCESSING' where id=?", orderId);
        events.deleteAll();

        consumers.dispatch(stockFailure(orderId));

        assertThat(orders.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PROCESSING);
        assertThat(events.count()).isZero();
    }

    @Test
    void outboxJobSurvivesBrokerOutage() {
        // OutboxJob nuốt AmqpException để scheduler không chết khi broker down.
        doThrow(new AmqpConnectException(new ConnectException("Connection refused")))
                .when(publisher)
                .publish(anyString(), any());
        checkout("COD");
        new OutboxJob(dispatcher).run();
        assertThat(events.findAll())
                .singleElement()
                .satisfies(
                        e -> {
                            assertThat(e.getState()).isEqualTo("PENDING");
                            assertThat(e.getRetryAttempts()).isEqualTo(1);
                        });
    }
}
