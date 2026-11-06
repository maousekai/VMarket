package com.vmarket.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
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
import com.vmarket.order.entity.OrderReturn;
import com.vmarket.order.entity.OrderStatus;
import com.vmarket.order.entity.ReturnStatus;
import com.vmarket.order.event.OutboxRepository;
import com.vmarket.order.repository.OrderRepository;
import com.vmarket.order.repository.OrderReturnRepository;
import com.vmarket.order.service.OrderCompletionJob;

/**
 * Kiểm thử tính năng hoàn tất đơn hàng tự động sau 7 ngày hoặc sớm bởi người mua (FR-ORD-02).
 */
@SpringBootTest(properties = "app.jobs.enabled=true")
@AutoConfigureMockMvc
class OrderCompletionJobTest {

	private static final String SECRET = "test-only-secret-0123456789-0123456789-0123456789";
	private static final String BUYER_USER = "01JBQ9USER00000000000BUYER";

	@Autowired private MockMvc mockMvc;
	@Autowired private OrderRepository orderRepository;
	@Autowired private OrderReturnRepository returnRepository;
	@Autowired private OutboxRepository outboxRepository;
	@Autowired private OrderCompletionJob completionJob;

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
		returnRepository.deleteAll();
		orderRepository.deleteAll();
	}

	private String buyerToken() {
		return TestTokens.bearer(TestTokens.accessToken(SECRET, BUYER_USER, List.of("BUYER")));
	}

	private Order createDeliveredOrder() {
		Order order = new Order();
		order.setUserId(BUYER_USER);
		order.setStatus(OrderStatus.DELIVERED);
		order.setPaymentMethod("COD");
		order.setRecipientName("Người mua");
		order.setPhone("0987654321");
		order.setProvince("Đà Nẵng");
		order.setDistrict("Hải Châu");
		order.setWard("Thạch Thang");
		order.setStreetAddress("123 Lê Duẩn");
		order.setTotalAmount(new BigDecimal("100000.00"));

		OrderItem item = new OrderItem();
		item.setOrder(order);
		item.setProductId("01JBQ9PROD0000000000000001");
		item.setShopId("01JBQ9SHOP0000000000000001");
		item.setQuantity(1);
		item.setUnitPrice(new BigDecimal("100000.00"));
		item.setLineTotal(new BigDecimal("100000.00"));
		order.getItems().add(item);

		return orderRepository.save(order);
	}

	@Test
	void buyerCompletesDeliveredOrderEarly_success() throws Exception {
		Order order = createDeliveredOrder();

		mockMvc.perform(post("/api/orders/" + order.getId() + "/complete")
				.header(HttpHeaders.AUTHORIZATION, buyerToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));

		Order updated = orderRepository.findById(order.getId()).orElseThrow();
		assertThat(updated.getStatus()).isEqualTo(OrderStatus.COMPLETED);
		assertThat(outboxRepository.count()).isEqualTo(1);
	}

	@Test
	void buyerCompletesProcessingOrder_returnsConflict() throws Exception {
		Order order = createDeliveredOrder();
		order.setStatus(OrderStatus.PROCESSING);
		orderRepository.save(order);

		mockMvc.perform(post("/api/orders/" + order.getId() + "/complete")
				.header(HttpHeaders.AUTHORIZATION, buyerToken()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("ORDER_NOT_COMPLETABLE"));
	}

	@Test
	void jobCompletesEligibleDeliveredOrder_withoutActiveReturns() {
		Order order = createDeliveredOrder();

		boolean completed = completionJob.completeIfEligible(order.getId());
		assertThat(completed).isTrue();

		Order updated = orderRepository.findById(order.getId()).orElseThrow();
		assertThat(updated.getStatus()).isEqualTo(OrderStatus.COMPLETED);
	}

	@Test
	void jobSkipsDeliveredOrder_whenHasActiveReturn() {
		Order order = createDeliveredOrder();

		OrderReturn orderReturn = new OrderReturn();
		orderReturn.setOrderId(order.getId());
		orderReturn.setUserId(BUYER_USER);
		orderReturn.setShopId("01JBQ9SHOP0000000000000001");
		orderReturn.setStatus(ReturnStatus.PENDING);
		orderReturn.setReason("Lỗi sản phẩm");
		returnRepository.save(orderReturn);

		boolean completed = completionJob.completeIfEligible(order.getId());
		assertThat(completed).isFalse();

		Order updated = orderRepository.findById(order.getId()).orElseThrow();
		assertThat(updated.getStatus()).isEqualTo(OrderStatus.DELIVERED);
	}
}
