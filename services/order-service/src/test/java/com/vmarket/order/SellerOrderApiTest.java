package com.vmarket.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.vmarket.order.client.CartServiceClient;
import com.vmarket.order.client.ShopServiceClient;
import com.vmarket.order.client.UserServiceClient;
import com.vmarket.order.entity.Order;
import com.vmarket.order.entity.OrderItem;
import com.vmarket.order.entity.OrderStatus;
import com.vmarket.order.event.OutboxRepository;
import com.vmarket.order.repository.OrderRepository;

/**
 * Kiểm thử API xử lý đơn hàng dành cho Seller (FR-ORD-04).
 */
@SpringBootTest
@AutoConfigureMockMvc
class SellerOrderApiTest {

	private static final String SECRET = "test-only-secret-0123456789-0123456789-0123456789";
	private static final String SELLER_USER = "01JBQ9SELLER00000000000001";
	private static final String OTHER_USER = "01JBQ9OTHER000000000000002";
	private static final String SHOP_1 = "01JBQ9SHOP0000000000000001";
	private static final String SHOP_2 = "01JBQ9SHOP0000000000000002";

	@Autowired private MockMvc mockMvc;
	@Autowired private OrderRepository orderRepository;
	@Autowired private OutboxRepository outboxRepository;

	@MockitoBean private UserServiceClient userServiceClient;
	@MockitoBean private CartServiceClient cartServiceClient;
	@MockitoBean private ShopServiceClient shopServiceClient;
	@MockitoBean private Clock clock;

	@BeforeEach
	void setup() {
		when(clock.instant()).thenReturn(Instant.parse("2026-11-06T12:00:00Z"));
		when(shopServiceClient.getMyShop(any()))
				.thenReturn(new ShopServiceClient.ShopView(SHOP_1, SELLER_USER, "Cửa hàng A", "APPROVED"));
	}

	@AfterEach
	void cleanUp() {
		outboxRepository.deleteAll();
		orderRepository.deleteAll();
	}

	private String sellerToken() {
		return TestTokens.bearer(TestTokens.accessToken(SECRET, SELLER_USER, List.of("SELLER")));
	}

	private String buyerToken() {
		return TestTokens.bearer(TestTokens.accessToken(SECRET, OTHER_USER, List.of("BUYER")));
	}

	private Order createTestOrder(String buyerId, String shopId, OrderStatus status) {
		Order order = new Order();
		order.setUserId(buyerId);
		order.setStatus(status);
		order.setPaymentMethod("COD");
		order.setRecipientName("Người mua");
		order.setPhone("0987654321");
		order.setProvince("Đà Nẵng");
		order.setDistrict("Hải Châu");
		order.setWard("Thạch Thang");
		order.setStreetAddress("123 Lê Duẩn");
		order.setTotalAmount(new BigDecimal("200000.00"));

		OrderItem item = new OrderItem();
		item.setOrder(order);
		item.setProductId("01JBQ9PROD0000000000000001");
		item.setShopId(shopId);
		item.setQuantity(2);
		item.setUnitPrice(new BigDecimal("100000.00"));
		item.setLineTotal(new BigDecimal("200000.00"));
		order.getItems().add(item);

		return orderRepository.save(order);
	}

	@Test
	void sellerListOrders_returnsOrdersBelongingToShop() throws Exception {
		createTestOrder(OTHER_USER, SHOP_1, OrderStatus.PENDING);
		createTestOrder(OTHER_USER, SHOP_2, OrderStatus.PENDING);

		mockMvc.perform(get("/api/orders/seller")
				.header(HttpHeaders.AUTHORIZATION, sellerToken())
				.param("shopId", SHOP_1))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].items[0].unitPrice").value(100000.0));
	}

	@Test
	void sellerGetOrder_returnsOnlyShopItems() throws Exception {
		Order order = createTestOrder(OTHER_USER, SHOP_1, OrderStatus.PENDING);

		mockMvc.perform(get("/api/orders/seller/" + order.getId())
				.header(HttpHeaders.AUTHORIZATION, sellerToken())
				.param("shopId", SHOP_1))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(order.getId()))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.shopTotalAmount").value(200000.0));
	}

	@Test
	void sellerGetOrder_notBelongingToShop_returnsNotFound() throws Exception {
		Order order = createTestOrder(OTHER_USER, SHOP_2, OrderStatus.PENDING);

		mockMvc.perform(get("/api/orders/seller/" + order.getId())
				.header(HttpHeaders.AUTHORIZATION, sellerToken())
				.param("shopId", SHOP_1))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
	}

	@Test
	void sellerConfirmOrder_pendingOrder_transitionsToProcessingAndEmitsEvent() throws Exception {
		Order order = createTestOrder(OTHER_USER, SHOP_1, OrderStatus.PENDING);

		mockMvc.perform(post("/api/orders/seller/" + order.getId() + "/confirm")
				.header(HttpHeaders.AUTHORIZATION, sellerToken())
				.param("shopId", SHOP_1))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PROCESSING"));

		Order updated = orderRepository.findById(order.getId()).orElseThrow();
		assertThat(updated.getStatus()).isEqualTo(OrderStatus.PROCESSING);
		assertThat(outboxRepository.count()).isEqualTo(1);
	}

	@Test
	void sellerConfirmOrder_waitingPayment_returnsConflict() throws Exception {
		Order order = createTestOrder(OTHER_USER, SHOP_1, OrderStatus.WAITING_PAYMENT);

		mockMvc.perform(post("/api/orders/seller/" + order.getId() + "/confirm")
				.header(HttpHeaders.AUTHORIZATION, sellerToken())
				.param("shopId", SHOP_1))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("ORDER_WAITING_PAYMENT"));
	}

	@Test
	void buyerCallingSellerEndpoint_returnsForbidden() throws Exception {
		mockMvc.perform(get("/api/orders/seller")
				.header(HttpHeaders.AUTHORIZATION, buyerToken()))
				.andExpect(status().isForbidden());
	}

	@Test
	void unauthenticatedCallingSellerEndpoint_returnsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/orders/seller"))
				.andExpect(status().isUnauthorized());
	}
}
