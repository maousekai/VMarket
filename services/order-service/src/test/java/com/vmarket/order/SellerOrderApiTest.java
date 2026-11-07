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
	void sellerAccessOtherShop_returnsForbidden() throws Exception {
		// Token thuộc SHOP_1 nhưng client truyền param shopId=SHOP_2 (tấn công IDOR)
		mockMvc.perform(get("/api/orders/seller")
				.header(HttpHeaders.AUTHORIZATION, sellerToken())
				.param("shopId", SHOP_2))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN_SHOP_ACCESS"));
	}

	@Test
	void multiShopOrder_eachShopConfirms_transitionsToProcessingWhenAllConfirmed() throws Exception {
		// Tạo đơn hàng gồm 2 item từ SHOP_1 và SHOP_2
		Order order = new Order();
		order.setUserId(OTHER_USER);
		order.setStatus(OrderStatus.PENDING);
		order.setPaymentMethod("COD");
		order.setRecipientName("Người mua");
		order.setPhone("0987654321");
		order.setProvince("Đà Nẵng");
		order.setDistrict("Hải Châu");
		order.setWard("Thạch Thang");
		order.setStreetAddress("123 Lê Duẩn");
		order.setTotalAmount(new BigDecimal("300000.00"));

		OrderItem item1 = new OrderItem();
		item1.setOrder(order);
		item1.setProductId("01JBQ9PROD0000000000000001");
		item1.setShopId(SHOP_1);
		item1.setQuantity(1);
		item1.setUnitPrice(new BigDecimal("100000.00"));
		item1.setLineTotal(new BigDecimal("100000.00"));
		order.getItems().add(item1);

		OrderItem item2 = new OrderItem();
		item2.setOrder(order);
		item2.setProductId("01JBQ9PROD0000000000000002");
		item2.setShopId(SHOP_2);
		item2.setQuantity(1);
		item2.setUnitPrice(new BigDecimal("200000.00"));
		item2.setLineTotal(new BigDecimal("200000.00"));
		order.getItems().add(item2);

		order = orderRepository.save(order);

		// 1. Seller 1 (SHOP_1) xác nhận đơn -> mặt hàng của SHOP_1 được confirmed, đơn vẫn PENDING vì SHOP_2 chưa xác nhận
		mockMvc.perform(post("/api/orders/seller/" + order.getId() + "/confirm")
				.header(HttpHeaders.AUTHORIZATION, sellerToken())
				.param("shopId", SHOP_1))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.shopConfirmed").value(true));

		Order check1 = orderRepository.findById(order.getId()).orElseThrow();
		assertThat(check1.getStatus()).isEqualTo(OrderStatus.PENDING);

		// Seller 1 cố tình xác nhận lần 2 -> bị chặn 409 SHOP_ALREADY_CONFIRMED
		mockMvc.perform(post("/api/orders/seller/" + order.getId() + "/confirm")
				.header(HttpHeaders.AUTHORIZATION, sellerToken())
				.param("shopId", SHOP_1))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("SHOP_ALREADY_CONFIRMED"));

		// 2. Đổi mock shopServiceClient sang SHOP_2 cho Seller 2
		String seller2Token = TestTokens.bearer(TestTokens.accessToken(SECRET, "01JBQ9SELLER00000000000002", List.of("SELLER")));
		when(shopServiceClient.getMyShop(seller2Token))
				.thenReturn(new ShopServiceClient.ShopView(SHOP_2, "01JBQ9SELLER00000000000002", "Cửa hàng B", "APPROVED"));

		// Seller 2 (SHOP_2) xác nhận đơn -> tất cả mặt hàng đã confirmed -> đơn chuyển thành PROCESSING
		mockMvc.perform(post("/api/orders/seller/" + order.getId() + "/confirm")
				.header(HttpHeaders.AUTHORIZATION, seller2Token)
				.param("shopId", SHOP_2))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PROCESSING"))
				.andExpect(jsonPath("$.shopConfirmed").value(true));

		Order check2 = orderRepository.findById(order.getId()).orElseThrow();
		assertThat(check2.getStatus()).isEqualTo(OrderStatus.PROCESSING);
		assertThat(outboxRepository.count()).isEqualTo(1);
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
