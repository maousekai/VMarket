package com.vmarket.order;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.http.MediaType;
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
 * Kiểm thử API giám sát đơn hàng toàn sàn dành cho Admin (FR-ORD-05).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminOrderApiTest {

	private static final String SECRET = "test-only-secret-0123456789-0123456789-0123456789";
	private static final String ADMIN_USER = "01JBQ9USER00000000000ADMIN";
	private static final String BUYER_USER = "01JBQ9USER00000000000BUYER";

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
	}

	@AfterEach
	void cleanUp() {
		outboxRepository.deleteAll();
		orderRepository.deleteAll();
	}

	private String adminToken() {
		return TestTokens.bearer(TestTokens.accessToken(SECRET, ADMIN_USER, List.of("ADMIN")));
	}

	private String buyerToken() {
		return TestTokens.bearer(TestTokens.accessToken(SECRET, BUYER_USER, List.of("BUYER")));
	}

	private Order createTestOrder(OrderStatus status) {
		Order order = new Order();
		order.setUserId(BUYER_USER);
		order.setStatus(status);
		order.setPaymentMethod("COD");
		order.setRecipientName("Người mua");
		order.setPhone("0987654321");
		order.setProvince("Đà Nẵng");
		order.setDistrict("Hải Châu");
		order.setWard("Thạch Thang");
		order.setStreetAddress("123 Lê Duẩn");
		order.setTotalAmount(new BigDecimal("150000.00"));

		OrderItem item = new OrderItem();
		item.setOrder(order);
		item.setProductId("01JBQ9PROD0000000000000001");
		item.setShopId("01JBQ9SHOP0000000000000001");
		item.setQuantity(1);
		item.setUnitPrice(new BigDecimal("150000.00"));
		item.setLineTotal(new BigDecimal("150000.00"));
		order.getItems().add(item);

		return orderRepository.save(order);
	}

	@Test
	void adminListOrders_returnsAllOrders() throws Exception {
		createTestOrder(OrderStatus.PENDING);
		createTestOrder(OrderStatus.PROCESSING);

		mockMvc.perform(get("/api/orders/admin")
				.header(HttpHeaders.AUTHORIZATION, adminToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(2));
	}

	@Test
	void adminListOrders_filtersByStatus() throws Exception {
		createTestOrder(OrderStatus.PENDING);
		createTestOrder(OrderStatus.PROCESSING);

		mockMvc.perform(get("/api/orders/admin")
				.header(HttpHeaders.AUTHORIZATION, adminToken())
				.param("status", "PROCESSING"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].status").value("PROCESSING"));
	}

	@Test
	void adminGetOrder_returnsDetail() throws Exception {
		Order order = createTestOrder(OrderStatus.SHIPPED);

		mockMvc.perform(get("/api/orders/admin/" + order.getId())
				.header(HttpHeaders.AUTHORIZATION, adminToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(order.getId()))
				.andExpect(jsonPath("$.status").value("SHIPPED"));
	}

	@Test
	void adminCancelOrder_success() throws Exception {
		Order order = createTestOrder(OrderStatus.PROCESSING);

		mockMvc.perform(post("/api/orders/admin/" + order.getId() + "/cancel")
				.header(HttpHeaders.AUTHORIZATION, adminToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"Người mua khiếu nại gian hàng không gửi hàng\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"));

		Order updated = orderRepository.findById(order.getId()).orElseThrow();
		assertThat(updated.getStatus()).isEqualTo(OrderStatus.CANCELLED);
		assertThat(outboxRepository.count()).isEqualTo(1);
	}

	@Test
	void adminCancelOrder_alreadyCancelled_returnsConflict() throws Exception {
		Order order = createTestOrder(OrderStatus.CANCELLED);

		mockMvc.perform(post("/api/orders/admin/" + order.getId() + "/cancel")
				.header(HttpHeaders.AUTHORIZATION, adminToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"Huỷ đơn\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("ORDER_ALREADY_CANCELLED"));
	}

	@Test
	void buyerCallingAdminEndpoint_returnsForbidden() throws Exception {
		mockMvc.perform(get("/api/orders/admin")
				.header(HttpHeaders.AUTHORIZATION, buyerToken()))
				.andExpect(status().isForbidden());
	}
}
