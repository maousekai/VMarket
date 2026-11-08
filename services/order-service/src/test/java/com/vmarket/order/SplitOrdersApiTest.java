package com.vmarket.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;
import com.vmarket.order.client.CartServiceClient;
import com.vmarket.order.client.CartServiceClient.CartGroupView;
import com.vmarket.order.client.CartServiceClient.CartIssueView;
import com.vmarket.order.client.CartServiceClient.CartItemView;
import com.vmarket.order.client.CartServiceClient.CartValidationView;
import com.vmarket.order.client.CartServiceClient.CartView;
import com.vmarket.order.client.UserServiceClient;
import com.vmarket.order.entity.Order;
import com.vmarket.order.exception.ApiException;
import com.vmarket.order.repository.OrderRepository;

/** FR-ORD-01: mỗi shop trong giỏ một đơn, chung checkoutId; validate giỏ trước khi tạo đơn. */
@SpringBootTest(properties = "app.payment.ttl=10m")
@AutoConfigureMockMvc
class SplitOrdersApiTest {

    private static final String SECRET = "test-only-secret-0123456789-0123456789-0123456789";
    private static final String CAROL = "01JBQ9USER000000000CAROL01";
    private static final String ADDRESS_ID = "01JBQ9ADDR0000000000TEST02";
    private static final String SHOP_A = "01JBQ9SHOP00000000000000A1";
    private static final String SHOP_B = "01JBQ9SHOP00000000000000B1";

    @Autowired private MockMvc mockMvc;
    @Autowired private OrderRepository orderRepository;
    @MockitoBean private UserServiceClient userServiceClient;
    @MockitoBean private CartServiceClient cartServiceClient;
    @MockitoBean private java.time.Clock paymentClock;

    @BeforeEach
    void setUp() {
        when(paymentClock.instant()).thenReturn(java.time.Instant.parse("2026-10-19T12:00:00Z"));
        when(userServiceClient.getAddress(anyString(), eq(ADDRESS_ID)))
                .thenReturn(
                        new UserServiceClient.AddressView(
                                ADDRESS_ID, "Trần Thị Carol", "0900000001", "Đà Nẵng", null,
                                "Hải Châu 1", "1 Bạch Đằng"));
        when(cartServiceClient.validateCart(CAROL)).thenReturn(new CartValidationView(true, List.of()));
        when(cartServiceClient.removeCartItem(eq(CAROL), anyString(), any())).thenReturn(true);
    }

    @AfterEach
    void cleanUp() {
        orderRepository.deleteAll();
    }

    private static CartItemView item(String product, String shop, int qty, String price) {
        BigDecimal unit = new BigDecimal(price);
        return new CartItemView(product, null, shop, qty, unit, unit.multiply(BigDecimal.valueOf(qty)));
    }

    private static CartView twoShopCart() {
        CartItemView a1 = item("01JBQ9PROD00000000000A0001", SHOP_A, 2, "100000.00");
        CartItemView a2 = item("01JBQ9PROD00000000000A0002", SHOP_A, 1, "50000.00");
        CartItemView b1 = item("01JBQ9PROD00000000000B0001", SHOP_B, 3, "20000.00");
        return new CartView(
                CAROL,
                List.of(
                        new CartGroupView(SHOP_A, List.of(a1, a2), new BigDecimal("250000.00")),
                        new CartGroupView(SHOP_B, List.of(b1), new BigDecimal("60000.00"))),
                6,
                new BigDecimal("310000.00"));
    }

    private static CartView oneShopCart() {
        CartItemView a1 = item("01JBQ9PROD00000000000A0001", SHOP_A, 2, "100000.00");
        return new CartView(
                CAROL,
                List.of(new CartGroupView(SHOP_A, List.of(a1), new BigDecimal("200000.00"))),
                2,
                new BigDecimal("200000.00"));
    }

    private String token() {
        return TestTokens.bearer(TestTokens.accessToken(SECRET, CAROL, List.of("BUYER")));
    }

    private MvcResult place(String body, String key) throws Exception {
        var req =
                post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body);
        if (key != null) {
            req = req.header("Idempotency-Key", key);
        }
        return mockMvc.perform(req).andReturn();
    }

    private static String body(String method) {
        return "{\"addressId\":\"" + ADDRESS_ID + "\",\"paymentMethod\":\"" + method + "\"}";
    }

    @Test
    void twoShopsCreateTwoOrdersWithSameCheckoutIdAndCorrectTotals() throws Exception {
        when(cartServiceClient.getCart(CAROL)).thenReturn(twoShopCart());

        mockMvc.perform(
                        post("/api/orders")
                                .header(HttpHeaders.AUTHORIZATION, token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("COD")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.checkoutId").isNotEmpty())
                .andExpect(jsonPath("$.totalAmount").value(310000.00))
                .andExpect(jsonPath("$.orders.length()").value(2))
                .andExpect(jsonPath("$.orders[0].shopId").value(SHOP_A))
                .andExpect(jsonPath("$.orders[0].totalAmount").value(250000.00))
                .andExpect(jsonPath("$.orders[0].items.length()").value(2))
                .andExpect(jsonPath("$.orders[0].status").value("PENDING"))
                .andExpect(jsonPath("$.orders[1].shopId").value(SHOP_B))
                .andExpect(jsonPath("$.orders[1].totalAmount").value(60000.00))
                .andExpect(jsonPath("$.orders[1].items.length()").value(1))
                .andExpect(jsonPath("$.orders[1].status").value("PENDING"));

        List<Order> saved = orderRepository.findByUserIdOrderByCreatedAtDesc(CAROL);
        assertThat(saved).hasSize(2);
        assertThat(saved).extracting(Order::getCheckoutId).doesNotContainNull().containsOnly(saved.get(0).getCheckoutId());
        for (Order o : saved) {
            assertThat(o.getItems()).extracting(i -> i.getShopId()).containsOnly(o.getShopId());
        }
        assertThat(saved).extracting(Order::getShopId).containsExactlyInAnyOrder(SHOP_A, SHOP_B);
        // Xoá giỏ cho toàn bộ 3 item.
        verify(cartServiceClient, times(3)).removeCartItem(eq(CAROL), anyString(), any());
    }

    @Test
    void oneShopCreatesOneOrder() throws Exception {
        when(cartServiceClient.getCart(CAROL)).thenReturn(oneShopCart());

        mockMvc.perform(
                        post("/api/orders")
                                .header(HttpHeaders.AUTHORIZATION, token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("COD")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.totalAmount").value(200000.00))
                .andExpect(jsonPath("$.orders[0].checkoutId").isNotEmpty());
        assertThat(orderRepository.findByUserIdOrderByCreatedAtDesc(CAROL)).hasSize(1);
    }

    @Test
    void payosMakesEveryOrderWaitingPayment() throws Exception {
        when(cartServiceClient.getCart(CAROL)).thenReturn(twoShopCart());

        mockMvc.perform(
                        post("/api/orders")
                                .header(HttpHeaders.AUTHORIZATION, token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("PAYOS")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orders.length()").value(2))
                .andExpect(jsonPath("$.orders[0].status").value("WAITING_PAYMENT"))
                .andExpect(jsonPath("$.orders[1].status").value("WAITING_PAYMENT"))
                .andExpect(jsonPath("$.orders[0].paymentExpiresAt").value("2026-10-19T12:10:00Z"))
                .andExpect(jsonPath("$.orders[1].paymentExpiresAt").value("2026-10-19T12:10:00Z"));
    }

    @Test
    void replayWithSameIdempotencyKeyReturnsSameOrdersAndCreatesNoMore() throws Exception {
        when(cartServiceClient.getCart(CAROL)).thenReturn(twoShopCart());

        MvcResult first = place(body("COD"), "split-key-1");
        MvcResult second = place(body("COD"), "split-key-1");

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        String json1 = first.getResponse().getContentAsString();
        String json2 = second.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(json2, "$.checkoutId"))
                .isEqualTo(JsonPath.read(json1, "$.checkoutId"));
        assertThat((List<String>) JsonPath.read(json2, "$.orders[*].id"))
                .containsExactlyElementsOf(JsonPath.<List<String>>read(json1, "$.orders[*].id"));
        assertThat(orderRepository.findByUserIdOrderByCreatedAtDesc(CAROL)).hasSize(2);
    }

    @Test
    void priceChangedValidationReturns409AndCreatesNoOrder() throws Exception {
        when(cartServiceClient.getCart(CAROL)).thenReturn(twoShopCart());
        when(cartServiceClient.validateCart(CAROL))
                .thenReturn(
                        new CartValidationView(
                                false,
                                List.of(
                                        new CartIssueView(
                                                "01JBQ9PROD00000000000A0001", null, "PRICE_CHANGED",
                                                "Áo thun: giá đã thay đổi", new BigDecimal("1000"),
                                                new BigDecimal("100000"), 2, null))));

        mockMvc.perform(
                        post("/api/orders")
                                .header(HttpHeaders.AUTHORIZATION, token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("COD")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CART_CHANGED"));

        assertThat(orderRepository.findByUserIdOrderByCreatedAtDesc(CAROL)).isEmpty();
        verify(cartServiceClient, never()).removeCartItem(anyString(), anyString(), any());
    }

    @Test
    void serviceUnavailableValidationReturns503() throws Exception {
        when(cartServiceClient.getCart(CAROL)).thenReturn(twoShopCart());
        when(cartServiceClient.validateCart(CAROL))
                .thenReturn(
                        new CartValidationView(
                                false,
                                List.of(
                                        new CartIssueView(
                                                null, null, "SERVICE_UNAVAILABLE",
                                                "Không kiểm tra được", null, null, null, null))));

        mockMvc.perform(
                        post("/api/orders")
                                .header(HttpHeaders.AUTHORIZATION, token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("COD")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("CART_VALIDATION_UNAVAILABLE"));
        assertThat(orderRepository.findByUserIdOrderByCreatedAtDesc(CAROL)).isEmpty();
    }

    @Test
    void cartServiceDownDuringValidationReturns503() throws Exception {
        when(cartServiceClient.getCart(CAROL)).thenReturn(oneShopCart());
        when(cartServiceClient.validateCart(CAROL))
                .thenThrow(
                        new ApiException(
                                "CART_VALIDATION_UNAVAILABLE",
                                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                                "down"));

        mockMvc.perform(
                        post("/api/orders")
                                .header(HttpHeaders.AUTHORIZATION, token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("COD")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("CART_VALIDATION_UNAVAILABLE"));
    }
}
