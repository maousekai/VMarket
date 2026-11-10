package com.vmarket.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vmarket.order.client.CartServiceClient;
import com.vmarket.order.client.UserServiceClient;
import com.vmarket.order.config.OrderJwtProperties;
import com.vmarket.order.entity.Order;
import com.vmarket.order.entity.OrderStatus;
import com.vmarket.order.repository.OrderRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

/**
 * Kiểm tra API đơn hàng qua MockMvc với token JWT thật: token hợp lệ thì nhận dữ liệu; thiếu/sai
 * token trả 401; Bob đoán đúng id cũng không thấy đơn của Alice (IDOR). UserServiceClient và
 * CartServiceClient đều mock ({@code @MockitoBean}) — test này chỉ kiểm tra nghiệp vụ của
 * order-service.
 */
@SpringBootTest(properties = "app.payment.ttl=10m")
@AutoConfigureMockMvc
class OrderApiTest {

    private static final String SECRET = "test-only-secret-0123456789-0123456789-0123456789";

    /* ULID 26 ký tự đúng chuẩn (BaseEntity.ID_LENGTH) — dài hơn sẽ vấp @Size(26). */
    private static final String ALICE = "01JBQ9USER000000000ALICE01";
    private static final String BOB = "01JBQ9USER0000000000BOB001";
    private static final String ADDRESS_ID = "01JBQ9ADDR0000000000TEST01";

    @Autowired private MockMvc mockMvc;

    @Autowired private OrderRepository orderRepository;

    @Autowired private OrderJwtProperties jwtProperties;

    @MockitoBean private UserServiceClient userServiceClient;

    @MockitoBean private CartServiceClient cartServiceClient;
    @MockitoBean private java.time.Clock paymentClock;

    @org.junit.jupiter.api.BeforeEach
    void paymentClock() {
        when(paymentClock.instant()).thenReturn(java.time.Instant.parse("2026-10-19T12:00:00Z"));
    }

    /**
     * Dọn bảng orders sau mỗi test: H2 chạy với {@code DB_CLOSE_DELAY=-1} nên dữ liệu của test
     * trước còn nguyên khi test sau bắt đầu — nếu không xoá thì {@code
     * listAndDetailOnlyShowOwnOrders} đếm thấy đơn của test khác và {@code placeOrder...} không còn
     * là giỏ "bình thường".
     */
    @AfterEach
    void cleanUp() {
        orderRepository.deleteAll();
    }

    private String aliceToken() {
        return TestTokens.bearer(TestTokens.accessToken(SECRET, ALICE, List.of("BUYER")));
    }

    private String bobToken() {
        return TestTokens.bearer(TestTokens.accessToken(SECRET, BOB, List.of("BUYER")));
    }

    private UserServiceClient.AddressView aliceAddress() {
        return new UserServiceClient.AddressView(
                ADDRESS_ID,
                "Nguyễn Văn An",
                "0912345678",
                "Đà Nẵng",
                "Hải Châu",
                "Thạch Thang",
                "54 Nguyễn Lương Bằng");
    }

    private CartServiceClient.CartView cartWithItems() {
        CartServiceClient.CartItemView item1 =
                new CartServiceClient.CartItemView(
                        "01JBQ9PROD000000000ITEM001",
                        null,
                        "01JBQ9SHOP0000000000TEST01",
                        2,
                        new BigDecimal("250000.00"),
                        new BigDecimal("500000.00"));
        CartServiceClient.CartItemView item2 =
                new CartServiceClient.CartItemView(
                        "01JBQ9PROD000000000ITEM002",
                        "01JBQ9VARI0000000000TEST01",
                        "01JBQ9SHOP0000000000TEST01",
                        1,
                        new BigDecimal("250000.00"),
                        new BigDecimal("250000.00"));
        return new CartServiceClient.CartView(
                ALICE,
                List.of(
                        new CartServiceClient.CartGroupView(
                                "01JBQ9SHOP0000000000TEST01",
                                List.of(item1, item2),
                                new BigDecimal("750000.00"))),
                3,
                new BigDecimal("750000.00"));
    }

    @Test
    void placeOrderWithoutTokenReturns401() throws Exception {
        mockMvc.perform(
                        post("/api/orders")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"addressId\":\"" + ADDRESS_ID + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void placeOrderWithWrongIssuerTokenReturns401() throws Exception {
        mockMvc.perform(
                        post("/api/orders")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        TestTokens.bearer(
                                                TestTokens.wrongIssuerToken(SECRET, ALICE)))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"addressId\":\"" + ADDRESS_ID + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void placeOrderWithMissingAddressIdReturns400() throws Exception {
        mockMvc.perform(
                        post("/api/orders")
                                .header(HttpHeaders.AUTHORIZATION, aliceToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"note\":\"thieu addressId\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        // Lỗi validation xảy ra trước khi gọi service nào — không có request nào
        // rời order-service.
        verifyNoInteractions(userServiceClient);
        verifyNoInteractions(cartServiceClient);
    }

    @Test
    void placeOrderCreatesSnapshotAndClearsCart() throws Exception {
        when(userServiceClient.getAddress(anyString(), eq(ADDRESS_ID))).thenReturn(aliceAddress());
        when(cartServiceClient.getCart(eq(ALICE))).thenReturn(cartWithItems());
        when(cartServiceClient.removeCartItem(eq(ALICE), anyString(), any())).thenReturn(true);

        mockMvc.perform(
                        post("/api/orders")
                                .header(HttpHeaders.AUTHORIZATION, aliceToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"addressId\":\""
                                                + ADDRESS_ID
                                                + "\",\"note\":\"Giao giờ hành chính\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.recipientName").value("Nguyễn Văn An"))
                .andExpect(jsonPath("$.totalAmount").value(750000.00))
                .andExpect(jsonPath("$.items.length()").value(2));

        // Tổng tiền được tính lại ở tầng service từ unitPrice * quantity, KHÔNG tin
        // cart-service; verify bằng dữ liệu thật trong DB chứ không chỉ response.
        // Thứ tự item không phụ thuộc ULID sinh ra — so theo tập productId.
        Order saved = orderRepository.findByUserIdOrderByCreatedAtDesc(ALICE).get(0);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(saved.getTotalAmount()).isEqualByComparingTo("750000.00");
        assertThat(saved.getRecipientName()).isEqualTo("Nguyễn Văn An");
        assertThat(saved.getItems()).hasSize(2);
        assertThat(saved.getItems())
                .extracting(i -> i.getProductId())
                .containsExactlyInAnyOrder(
                        "01JBQ9PROD000000000ITEM001", "01JBQ9PROD000000000ITEM002");
        assertThat(saved.getItems())
                .extracting(i -> i.getLineTotal())
                .containsExactlyInAnyOrder(
                        new BigDecimal("500000.00"), new BigDecimal("250000.00"));

        verify(cartServiceClient, times(2)).removeCartItem(eq(ALICE), anyString(), any());
    }

    @Test
    void placeOrderWithAddressWithoutDistrictSucceeds() throws Exception {
        // Địa chỉ tạo từ UI mới không có quận/huyện (Đà Nẵng bỏ cấp này từ 01/07/2025).
        UserServiceClient.AddressView address =
                new UserServiceClient.AddressView(
                        ADDRESS_ID,
                        "Nguyễn Văn An",
                        "0912345678",
                        "Đà Nẵng",
                        null,
                        "Thạch Thang",
                        "54 Nguyễn Lương Bằng");
        when(userServiceClient.getAddress(anyString(), eq(ADDRESS_ID))).thenReturn(address);
        when(cartServiceClient.getCart(eq(ALICE))).thenReturn(cartWithItems());
        when(cartServiceClient.removeCartItem(eq(ALICE), anyString(), any())).thenReturn(true);

        mockMvc.perform(
                        post("/api/orders")
                                .header(HttpHeaders.AUTHORIZATION, aliceToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"addressId\":\"" + ADDRESS_ID + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.district").value(nullValue()));

        Order saved = orderRepository.findByUserIdOrderByCreatedAtDesc(ALICE).get(0);
        assertThat(saved.getDistrict()).isNull();
    }

    @Test
    void placeOrderKeepsCatalogIdsLongerThanUlids() throws Exception {
        // Product Catalog cấp ObjectId (24 ký tự) cho sản phẩm và UUID (36 ký tự) cho biến
        // thể; cột VARCHAR(26) cũ làm mọi đơn có biến thể thật lỗi 500 (PBL6-30).
        String productId = "6aeda9255cb56e8b256e2463";
        String variantId = "136dd277-76d9-4038-91b5-ab8c18f894a4";
        CartServiceClient.CartItemView item =
                new CartServiceClient.CartItemView(
                        productId,
                        variantId,
                        "01JBQ9SHOP0000000000TEST01",
                        1,
                        new BigDecimal("150000.00"),
                        new BigDecimal("150000.00"));
        when(userServiceClient.getAddress(anyString(), eq(ADDRESS_ID))).thenReturn(aliceAddress());
        when(cartServiceClient.getCart(eq(ALICE)))
                .thenReturn(
                        new CartServiceClient.CartView(
                                ALICE,
                                List.of(
                                        new CartServiceClient.CartGroupView(
                                                "01JBQ9SHOP0000000000TEST01",
                                                List.of(item),
                                                new BigDecimal("150000.00"))),
                                1,
                                new BigDecimal("150000.00")));
        when(cartServiceClient.removeCartItem(eq(ALICE), anyString(), any())).thenReturn(true);

        mockMvc.perform(
                        post("/api/orders")
                                .header(HttpHeaders.AUTHORIZATION, aliceToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"addressId\":\"" + ADDRESS_ID + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].productId").value(productId))
                .andExpect(jsonPath("$.items[0].variantId").value(variantId));
    }

    @Test
    void payosDeadlineUsesConfiguredDurationAndInjectedClock() throws Exception {
        when(userServiceClient.getAddress(anyString(), eq(ADDRESS_ID))).thenReturn(aliceAddress());
        when(cartServiceClient.getCart(eq(ALICE))).thenReturn(cartWithItems());
        when(cartServiceClient.removeCartItem(eq(ALICE), anyString(), any())).thenReturn(true);
        mockMvc.perform(
                        post("/api/orders")
                                .header(HttpHeaders.AUTHORIZATION, aliceToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"addressId\":\""
                                                + ADDRESS_ID
                                                + "\",\"paymentMethod\":\"PAYOS\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING_PAYMENT"))
                .andExpect(jsonPath("$.paymentExpiresAt").value("2026-10-19T12:10:00Z"));
    }

    @Test
    void placeOrderWithEmptyCartReturns400() throws Exception {
        when(userServiceClient.getAddress(anyString(), eq(ADDRESS_ID))).thenReturn(aliceAddress());
        when(cartServiceClient.getCart(eq(ALICE)))
                .thenReturn(new CartServiceClient.CartView(ALICE, List.of(), 0, BigDecimal.ZERO));

        mockMvc.perform(
                        post("/api/orders")
                                .header(HttpHeaders.AUTHORIZATION, aliceToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"addressId\":\"" + ADDRESS_ID + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CART_EMPTY"));

        // Không đặt được thì không xoá giỏ, không ghi đơn.
        verify(cartServiceClient, never()).removeCartItem(anyString(), anyString(), any());
        verify(cartServiceClient, never()).clearCart(anyString());
        assertThat(orderRepository.findByUserIdOrderByCreatedAtDesc(ALICE)).isEmpty();
    }

    // Test placeOrderWithForeignAddressReturns404 đã bỏ: user-service endpoint
    // /me/addresses/{id} đã kiểm tra ownership bằng JWT, order-service không cần
    // kiểm tra userId nữa (P1 review PR #25).

    @Test
    void listAndDetailOnlyShowOwnOrders() throws Exception {
        createOrderForAlice();

        // Alice thấy đơn của mình.
        mockMvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, aliceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("PENDING"));

        // Chi tiết trả kèm id từng dòng hàng: buyer cần nó làm orderItemId khi trả hàng.
        Order own = orderRepository.findByUserIdOrderByCreatedAtDesc(ALICE).get(0);
        String orderId = own.getId();
        mockMvc.perform(get("/api/orders/" + orderId).header(HttpHeaders.AUTHORIZATION, aliceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id")
                        .value(org.hamcrest.Matchers.containsInAnyOrder(own.getItems().stream()
                                .map(i -> i.getId()).toArray())))
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.deliveredAt").value(org.hamcrest.Matchers.nullValue()));

        // Bob đoán đúng id cũng không đọc được (IDOR).
        mockMvc.perform(get("/api/orders/" + orderId).header(HttpHeaders.AUTHORIZATION, bobToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void cancelPendingOrderSucceeds() throws Exception {
        createOrderForAlice();
        String orderId = orderRepository.findByUserIdOrderByCreatedAtDesc(ALICE).get(0).getId();

        mockMvc.perform(
                        put("/api/orders/" + orderId + "/cancel")
                                .header(HttpHeaders.AUTHORIZATION, aliceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancelProcessedOrderReturns409() throws Exception {
        // Đơn PROCESSING (đã xác nhận) do test tự chèn — huỷ phải bị từ chối 409.
        Order processing = new Order();
        processing.setUserId(ALICE);
        processing.setStatus(OrderStatus.PROCESSING);
        processing.setRecipientName("Nguyễn Văn An");
        processing.setPhone("0912345678");
        processing.setProvince("Đà Nẵng");
        processing.setDistrict("Hải Châu");
        processing.setWard("Thạch Thang");
        processing.setStreetAddress("54 Nguyễn Lương Bằng");
        processing.setTotalAmount(new BigDecimal("1000.00"));
        processing = orderRepository.save(processing);

        mockMvc.perform(
                        put("/api/orders/" + processing.getId() + "/cancel")
                                .header(HttpHeaders.AUTHORIZATION, aliceToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_CANCELLABLE"));

        // Trạng thái không đổi sau lần huỷ bị từ chối.
        assertThat(orderRepository.findById(processing.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PROCESSING);
    }

    /** Tạo một đơn PENDING cho Alice qua chính API (mock cart/user như bình thường). */
    private void createOrderForAlice() throws Exception {
        when(userServiceClient.getAddress(anyString(), eq(ADDRESS_ID))).thenReturn(aliceAddress());
        when(cartServiceClient.getCart(eq(ALICE))).thenReturn(cartWithItems());
        when(cartServiceClient.removeCartItem(eq(ALICE), anyString(), any())).thenReturn(true);
        mockMvc.perform(
                        post("/api/orders")
                                .header(HttpHeaders.AUTHORIZATION, aliceToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"addressId\":\"" + ADDRESS_ID + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void placeOrderWithIdempotencyKeyReturnsExistingOrder() throws Exception {
        when(userServiceClient.getAddress(anyString(), eq(ADDRESS_ID))).thenReturn(aliceAddress());
        when(cartServiceClient.getCart(eq(ALICE))).thenReturn(cartWithItems());
        when(cartServiceClient.removeCartItem(eq(ALICE), anyString(), any())).thenReturn(true);

        String key = "test-idempotency-key-123";

        mockMvc.perform(
                        post("/api/orders")
                                .header(HttpHeaders.AUTHORIZATION, aliceToken())
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"addressId\":\"" + ADDRESS_ID + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));

        // Gửi lại cùng key -> vẫn trả 201 kèm dữ liệu đơn cũ, không tạo thêm đơn mới
        mockMvc.perform(
                        post("/api/orders")
                                .header(HttpHeaders.AUTHORIZATION, aliceToken())
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"addressId\":\"" + ADDRESS_ID + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));

        // Chỉ có đúng 1 đơn được lưu trong database
        assertThat(orderRepository.findByUserIdOrderByCreatedAtDesc(ALICE)).hasSize(1);
    }

    @Test
    void placeOrderWithConcurrentRetriesReturnsSameOrder() throws Exception {
        int threads = 6;
        java.util.concurrent.CyclicBarrier cartBarrier =
                new java.util.concurrent.CyclicBarrier(threads);

        when(userServiceClient.getAddress(anyString(), eq(ADDRESS_ID))).thenReturn(aliceAddress());
        // Sử dụng CyclicBarrier trong getCart() để đảm bảo tất cả 6 threads đều vượt qua
        // bước kiểm tra idempotency ban đầu và cùng lúc ập vào bước insert order, tái hiện 100%
        // điều kiện race condition trên database unique constraint.
        when(cartServiceClient.getCart(eq(ALICE)))
                .thenAnswer(
                        invocation -> {
                            try {
                                cartBarrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                            } catch (Exception e) {
                                throw new RuntimeException("Barrier timeout", e);
                            }
                            return cartWithItems();
                        });
        when(cartServiceClient.removeCartItem(eq(ALICE), anyString(), any())).thenReturn(true);

        String key = "concurrent-idempotency-key-" + System.currentTimeMillis();
        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch readyLatch =
                new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.CountDownLatch startLatch =
                new java.util.concurrent.CountDownLatch(1);
        java.util.List<org.springframework.test.web.servlet.MvcResult> results =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.List<Throwable> errors =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        for (int i = 0; i < threads; i++) {
            executor.submit(
                    () -> {
                        readyLatch.countDown();
                        try {
                            startLatch.await();
                            org.springframework.test.web.servlet.MvcResult result =
                                    mockMvc.perform(
                                                    post("/api/orders")
                                                            .header(
                                                                    HttpHeaders.AUTHORIZATION,
                                                                    aliceToken())
                                                            .header("Idempotency-Key", key)
                                                            .contentType(
                                                                    MediaType.APPLICATION_JSON)
                                                            .content(
                                                                    "{\"addressId\":\""
                                                                            + ADDRESS_ID
                                                                            + "\"}"))
                                            .andReturn();
                            results.add(result);
                        } catch (Throwable t) {
                            errors.add(t);
                        }
                    });
        }

        readyLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        startLatch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(errors).isEmpty();
        assertThat(results).hasSize(threads);

        // Mọi request retry song song đều phải thành công (201 Created) và trả về cùng một đơn
        String firstOrderId = null;
        for (org.springframework.test.web.servlet.MvcResult result : results) {
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            String content = result.getResponse().getContentAsString();
            String orderId = com.jayway.jsonpath.JsonPath.read(content, "$.id");
            assertThat(orderId).isNotBlank();
            if (firstOrderId == null) {
                firstOrderId = orderId;
            } else {
                assertThat(orderId).isEqualTo(firstOrderId);
            }
        }

        // Database chỉ lưu duy nhất 1 đơn cho idempotency key này
        assertThat(orderRepository.findByUserIdOrderByCreatedAtDesc(ALICE)).hasSize(1);
    }

    @Test
    void placeOrderConcurrentRetryWhenCartAlreadyEmptiedReturnsExistingOrder() throws Exception {
        when(userServiceClient.getAddress(anyString(), eq(ADDRESS_ID))).thenReturn(aliceAddress());
        when(cartServiceClient.removeCartItem(eq(ALICE), anyString(), any())).thenReturn(true);

        String key = "retry-empty-cart-" + System.currentTimeMillis();

        // Đồng bộ hóa để kiểm soát chính xác thứ tự race condition:
        // 1. Request A bắt đầu -> kiểm tra idempotency (chưa có đơn) -> tiến hành đọc giỏ.
        // 2. Khi Request A chạm vào getCart(), Request B xen vào tạo đơn hoàn tất với cùng key và làm rỗng giỏ.
        // 3. Request A tiếp tục đọc giỏ và nhận kết quả rỗng (empty cart).
        // 4. Request A kiểm tra lại idempotency -> phát hiện đơn Request B vừa commit -> trả về 201 Created (thay vì 400 CART_EMPTY).
        java.util.concurrent.CountDownLatch aAtCart = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch bCommitted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean aFirstCall = new java.util.concurrent.atomic.AtomicBoolean(true);

        when(cartServiceClient.getCart(eq(ALICE))).thenAnswer(inv -> {
            if (aFirstCall.compareAndSet(true, false)) {
                // Request A đọc giỏ: báo hiệu B bắt đầu và tạm dừng chờ B tạo đơn xong
                aAtCart.countDown();
                try {
                    bCommitted.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException("Timeout waiting for Request B", e);
                }
                // Lúc này B đã tạo đơn và dọn giỏ, trả về giỏ rỗng cho Request A
                return new com.vmarket.order.client.CartServiceClient.CartView(
                        ALICE, java.util.List.of(), 0, BigDecimal.ZERO);
            }
            // Request B đọc giỏ: trả về giỏ đầy đủ để tạo đơn thành công
            return cartWithItems();
        });

        // Request A chạy bất đồng bộ trong background thread
        java.util.concurrent.CompletableFuture<org.springframework.test.web.servlet.MvcResult> futureA =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        return mockMvc.perform(
                                post("/api/orders")
                                        .header(HttpHeaders.AUTHORIZATION, aliceToken())
                                        .header("Idempotency-Key", key)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"addressId\":\"" + ADDRESS_ID + "\"}"))
                                .andReturn();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

        // Chờ Request A vượt qua bước check idempotency ban đầu và chạm vào getCart()
        assertThat(aAtCart.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        // Request B tạo đơn với cùng key khi giỏ còn hàng -> commit đơn thành công
        var resB = mockMvc.perform(
                        post("/api/orders")
                                .header(HttpHeaders.AUTHORIZATION, aliceToken())
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"addressId\":\"" + ADDRESS_ID + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        String orderIdB = com.jayway.jsonpath.JsonPath.read(resB.getResponse().getContentAsString(), "$.id");

        // Báo hiệu Request B đã commit xong
        bCommitted.countDown();

        // Request A hoàn tất: nhận giỏ rỗng nhưng phát hiện đơn B đã tạo -> trả về 201 Created cùng ID
        org.springframework.test.web.servlet.MvcResult resA = futureA.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(resA.getResponse().getStatus()).isEqualTo(201);
        String orderIdA = com.jayway.jsonpath.JsonPath.read(resA.getResponse().getContentAsString(), "$.id");
        assertThat(orderIdA).isEqualTo(orderIdB);

        // Database chỉ lưu duy nhất 1 đơn
        var orders = orderRepository.findByUserIdOrderByCreatedAtDesc(ALICE);
        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).getId()).isEqualTo(orderIdB);
    }

    @Test
    void placeOrderWhenCartItemRemovalFailsPartially_orderStillCreatedSuccessfully() throws Exception {
        when(userServiceClient.getAddress(anyString(), eq(ADDRESS_ID))).thenReturn(aliceAddress());
        when(cartServiceClient.getCart(eq(ALICE))).thenReturn(cartWithItems());
        // Giả lập item đầu xoá thành công, item sau xoá thất bại (người dùng tự xoá hoặc thay đổi giỏ đồng thời)
        when(cartServiceClient.removeCartItem(eq(ALICE), anyString(), any()))
                .thenReturn(true)
                .thenReturn(false);

        mockMvc.perform(
                        post("/api/orders")
                                .header(HttpHeaders.AUTHORIZATION, aliceToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"addressId\":\"" + ADDRESS_ID + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));

        assertThat(orderRepository.findByUserIdOrderByCreatedAtDesc(ALICE)).hasSize(1);
    }

    private CartServiceClient.CartItemView line(String product, String shop, int qty, String price) {
        BigDecimal unit = new BigDecimal(price);
        return new CartServiceClient.CartItemView(
                product, null, shop, qty, unit, unit.multiply(BigDecimal.valueOf(qty)));
    }

    @Test
    void checkoutSplitsSelectedItemsIntoOneOrderPerShop() throws Exception {
        String shopA = "01JBQ9SHOP0000000000SHOPAA";
        String shopB = "01JBQ9SHOP0000000000SHOPBB";
        var a1 = line("01JBQ9PROD0000000000PRODA1", shopA, 1, "100000.00");
        var a2 = line("01JBQ9PROD0000000000PRODA2", shopA, 2, "50000.00");
        var b1 = line("01JBQ9PROD0000000000PRODB1", shopB, 1, "70000.00");
        var notChosen = line("01JBQ9PROD0000000000PRODB2", shopB, 1, "10000.00");
        when(userServiceClient.getAddress(anyString(), eq(ADDRESS_ID))).thenReturn(aliceAddress());
        when(cartServiceClient.getCart(eq(ALICE)))
                .thenReturn(
                        new CartServiceClient.CartView(
                                ALICE,
                                List.of(
                                        new CartServiceClient.CartGroupView(
                                                shopA, List.of(a1, a2), new BigDecimal("200000.00")),
                                        new CartServiceClient.CartGroupView(
                                                shopB, List.of(b1, notChosen), new BigDecimal("80000.00"))),
                                5,
                                new BigDecimal("280000.00")));
        when(cartServiceClient.removeCartItem(eq(ALICE), anyString(), any())).thenReturn(true);
        String body =
                "{\"addressId\":\""
                        + ADDRESS_ID
                        + "\",\"items\":["
                        + "{\"productId\":\"01JBQ9PROD0000000000PRODA1\"},"
                        + "{\"productId\":\"01JBQ9PROD0000000000PRODA2\"},"
                        + "{\"productId\":\"01JBQ9PROD0000000000PRODB1\"}]}";

        mockMvc.perform(
                        post("/api/orders/checkout")
                                .header(HttpHeaders.AUTHORIZATION, aliceToken())
                                .header("Idempotency-Key", "checkout-key-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].items.length()").value(2))
                .andExpect(jsonPath("$[0].totalAmount").value(200000.00))
                .andExpect(jsonPath("$[1].items.length()").value(1))
                .andExpect(jsonPath("$[1].totalAmount").value(70000.00));

        assertThat(orderRepository.findByUserIdOrderByCreatedAtDesc(ALICE)).hasSize(2);
        verify(cartServiceClient, never()).removeCartItem(ALICE, "01JBQ9PROD0000000000PRODB2", null);
        verify(cartServiceClient).removeCartItem(ALICE, "01JBQ9PROD0000000000PRODB1", null);

        // Gửi lại cùng khoá: không tạo thêm đơn.
        mockMvc.perform(
                        post("/api/orders/checkout")
                                .header(HttpHeaders.AUTHORIZATION, aliceToken())
                                .header("Idempotency-Key", "checkout-key-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(2));
        assertThat(orderRepository.findByUserIdOrderByCreatedAtDesc(ALICE)).hasSize(2);
    }

    @Test
    void checkoutWithNothingSelectedInCartIsRejected() throws Exception {
        when(userServiceClient.getAddress(anyString(), eq(ADDRESS_ID))).thenReturn(aliceAddress());
        when(cartServiceClient.getCart(eq(ALICE))).thenReturn(cartWithItems());
        mockMvc.perform(
                        post("/api/orders/checkout")
                                .header(HttpHeaders.AUTHORIZATION, aliceToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"addressId\":\""
                                                + ADDRESS_ID
                                                + "\",\"items\":[{\"productId\":\"not-in-cart\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CART_EMPTY"));
    }
}
