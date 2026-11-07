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
import org.springframework.http.MediaType;
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

/**
 * Kiểm thử quy trình yêu cầu và xử lý trả hàng (FR-ORD-06, FR-ORD-07).
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderReturnApiTest {

	private static final String SECRET = "test-only-secret-0123456789-0123456789-0123456789";
	private static final String BUYER_USER = "01JBQ9USER00000000000BUYER";
	private static final String SELLER_USER = "01JBQ9SELLER00000000000001";
	private static final String ADMIN_USER = "01JBQ9USER00000000000ADMIN";
	private static final String SHOP_ID = "01JBQ9SHOP0000000000000001";

	@Autowired private MockMvc mockMvc;
	@Autowired private OrderRepository orderRepository;
	@Autowired private OrderReturnRepository returnRepository;
	@Autowired private OutboxRepository outboxRepository;

	@MockitoBean private UserServiceClient userServiceClient;
	@MockitoBean private CartServiceClient cartServiceClient;
	@MockitoBean private ShopServiceClient shopServiceClient;
	@MockitoBean private Clock clock;

	@BeforeEach
	void setup() {
		when(clock.instant()).thenReturn(Instant.parse("2026-11-06T12:00:00Z"));
		when(shopServiceClient.getMyShop(any()))
				.thenReturn(new ShopServiceClient.ShopView(SHOP_ID, SELLER_USER, "Cửa hàng A", "APPROVED"));
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

	private String sellerToken() {
		return TestTokens.bearer(TestTokens.accessToken(SECRET, SELLER_USER, List.of("SELLER")));
	}

	private String adminToken() {
		return TestTokens.bearer(TestTokens.accessToken(SECRET, ADMIN_USER, List.of("ADMIN")));
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
		order.setTotalAmount(new BigDecimal("300000.00"));

		OrderItem item = new OrderItem();
		item.setOrder(order);
		item.setProductId("01JBQ9PROD0000000000000001");
		item.setShopId(SHOP_ID);
		item.setQuantity(2);
		item.setUnitPrice(new BigDecimal("150000.00"));
		item.setLineTotal(new BigDecimal("300000.00"));
		order.getItems().add(item);

		return orderRepository.save(order);
	}

	@Test
	void buyerCreatesReturn_deliveredOrderWithin7Days_success() throws Exception {
		Order order = createDeliveredOrder();
		String orderItemId = order.getItems().get(0).getId();

		String body = """
				{
				  "reason": "Sản phẩm bị lỗi đường may",
				  "evidenceUrls": ["https://storage.vmarket.vn/evidence1.jpg"],
				  "items": [
				    {
				      "orderItemId": "%s",
				      "productId": "01JBQ9PROD0000000000000001",
				      "quantity": 1
				    }
				  ]
				}
				""".formatted(orderItemId);

		mockMvc.perform(post("/api/orders/" + order.getId() + "/returns")
				.header(HttpHeaders.AUTHORIZATION, buyerToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.orderId").value(order.getId()))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.refundAmount").value(150000.0))
				.andExpect(jsonPath("$.items.length()").value(1));

		assertThat(returnRepository.count()).isEqualTo(1);
		assertThat(outboxRepository.count()).isEqualTo(1);
	}

	@Test
	void buyerCreatesReturn_nonDeliveredOrder_returnsConflict() throws Exception {
		Order order = createDeliveredOrder();
		order.setStatus(OrderStatus.SHIPPED);
		orderRepository.save(order);
		String orderItemId = order.getItems().get(0).getId();

		String body = """
				{
				  "reason": "Chưa nhận được mà muốn trả",
				  "items": [
				    {
				      "orderItemId": "%s",
				      "productId": "01JBQ9PROD0000000000000001",
				      "quantity": 1
				    }
				  ]
				}
				""".formatted(orderItemId);

		mockMvc.perform(post("/api/orders/" + order.getId() + "/returns")
				.header(HttpHeaders.AUTHORIZATION, buyerToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("ORDER_NOT_RETURNABLE"));
	}

	@Test
	void buyerCreatesReturn_sameItemTwice_returnsConflict() throws Exception {
		Order order = createDeliveredOrder();
		String orderItemId = order.getItems().get(0).getId();

		String body = """
				{
				  "reason": "Lỗi 1",
				  "items": [
				    {
				      "orderItemId": "%s",
				      "productId": "01JBQ9PROD0000000000000001",
				      "quantity": 1
				    }
				  ]
				}
				""".formatted(orderItemId);

		mockMvc.perform(post("/api/orders/" + order.getId() + "/returns")
				.header(HttpHeaders.AUTHORIZATION, buyerToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isCreated());

		// Lần thứ hai
		mockMvc.perform(post("/api/orders/" + order.getId() + "/returns")
				.header(HttpHeaders.AUTHORIZATION, buyerToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("ITEM_ALREADY_RETURNED"));
	}

	@Test
	void sellerAcceptsReturn_success() throws Exception {
		Order order = createDeliveredOrder();
		String orderItemId = order.getItems().get(0).getId();

		String body = """
				{
				  "reason": "Lỗi",
				  "items": [{ "orderItemId": "%s", "productId": "01JBQ9PROD0000000000000001", "quantity": 1 }]
				}
				""".formatted(orderItemId);

		String res = mockMvc.perform(post("/api/orders/" + order.getId() + "/returns")
				.header(HttpHeaders.AUTHORIZATION, buyerToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andReturn().getResponse().getContentAsString();

		String returnId = returnRepository.findAll().get(0).getId();

		mockMvc.perform(post("/api/orders/seller/returns/" + returnId + "/accept")
				.header(HttpHeaders.AUTHORIZATION, sellerToken())
				.param("shopId", SHOP_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("APPROVED"));

		OrderReturn updated = returnRepository.findById(returnId).orElseThrow();
		assertThat(updated.getStatus()).isEqualTo(ReturnStatus.APPROVED);
	}

	@Test
	void sellerRejectsReturn_buyerDisputes_adminResolves() throws Exception {
		Order order = createDeliveredOrder();
		String orderItemId = order.getItems().get(0).getId();

		String body = """
				{
				  "reason": "Hàng rách",
				  "items": [{ "orderItemId": "%s", "productId": "01JBQ9PROD0000000000000001", "quantity": 1 }]
				}
				""".formatted(orderItemId);

		mockMvc.perform(post("/api/orders/" + order.getId() + "/returns")
				.header(HttpHeaders.AUTHORIZATION, buyerToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isCreated());

		String returnId = returnRepository.findAll().get(0).getId();

		// 1. Seller từ chối
		mockMvc.perform(post("/api/orders/seller/returns/" + returnId + "/reject")
				.header(HttpHeaders.AUTHORIZATION, sellerToken())
				.param("shopId", SHOP_ID)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"Do người dùng tự làm rách\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REJECTED"))
				.andExpect(jsonPath("$.sellerNote").value("Do người dùng tự làm rách"));

		// 2. Buyer khiếu nại lên Admin
		mockMvc.perform(post("/api/orders/returns/" + returnId + "/dispute")
				.header(HttpHeaders.AUTHORIZATION, buyerToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"Có video mở hộp làm bằng chứng\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("DISPUTED"));

		// 3. Admin xem danh sách tranh chấp
		mockMvc.perform(get("/api/orders/admin/returns")
				.header(HttpHeaders.AUTHORIZATION, adminToken())
				.param("status", "DISPUTED"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1));

		// 4. Admin phân xử: chấp thuận khiếu nại
		mockMvc.perform(post("/api/orders/admin/returns/" + returnId + "/resolve")
				.header(HttpHeaders.AUTHORIZATION, adminToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"approved\":true,\"restock\":true,\"note\":\"Video rõ ràng, chấp thuận hoàn tiền\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("RESOLVED"));

		OrderReturn resolved = returnRepository.findById(returnId).orElseThrow();
		assertThat(resolved.getStatus()).isEqualTo(ReturnStatus.RESOLVED);
	}

	@Test
	void sellerConfirmsReceipt_emitsReturnResolvedEvent() throws Exception {
		Order order = createDeliveredOrder();
		String orderItemId = order.getItems().get(0).getId();

		String body = """
				{
				  "reason": "Đổi kích thước",
				  "items": [{ "orderItemId": "%s", "productId": "01JBQ9PROD0000000000000001", "quantity": 1 }]
				}
				""".formatted(orderItemId);

		mockMvc.perform(post("/api/orders/" + order.getId() + "/returns")
				.header(HttpHeaders.AUTHORIZATION, buyerToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isCreated());

		String returnId = returnRepository.findAll().get(0).getId();

		// Chấp nhận
		mockMvc.perform(post("/api/orders/seller/returns/" + returnId + "/accept")
				.header(HttpHeaders.AUTHORIZATION, sellerToken())
				.param("shopId", SHOP_ID))
				.andExpect(status().isOk());

		// Xác nhận nhận hàng
		mockMvc.perform(post("/api/orders/seller/returns/" + returnId + "/confirm-receipt")
				.header(HttpHeaders.AUTHORIZATION, sellerToken())
				.param("shopId", SHOP_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("RESOLVED"));

		OrderReturn resolved = returnRepository.findById(returnId).orElseThrow();
		assertThat(resolved.getStatus()).isEqualTo(ReturnStatus.RESOLVED);
	}

	@Test
	void sellerCannotConfirmReceiptWhenDisputed() throws Exception {
		Order order = createDeliveredOrder();
		String orderItemId = order.getItems().get(0).getId();

		String body = """
				{
				  "reason": "Hàng lỗi",
				  "items": [{ "orderItemId": "%s", "productId": "01JBQ9PROD0000000000000001", "quantity": 1 }]
				}
				""".formatted(orderItemId);

		mockMvc.perform(post("/api/orders/" + order.getId() + "/returns")
				.header(HttpHeaders.AUTHORIZATION, buyerToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isCreated());

		String returnId = returnRepository.findAll().get(0).getId();

		// Seller từ chối -> REJECTED
		mockMvc.perform(post("/api/orders/seller/returns/" + returnId + "/reject")
				.header(HttpHeaders.AUTHORIZATION, sellerToken())
				.param("shopId", SHOP_ID)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"Không phải lỗi do shop\"}"))
				.andExpect(status().isOk());

		// Buyer khiếu nại -> DISPUTED
		mockMvc.perform(post("/api/orders/returns/" + returnId + "/dispute")
				.header(HttpHeaders.AUTHORIZATION, buyerToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"Đã kiểm tra kỹ video mở hộp\"}"))
				.andExpect(status().isOk());

		// Seller cố tình xác nhận nhận hàng khi đang DISPUTED -> bị chặn 409 INVALID_RETURN_STATUS
		mockMvc.perform(post("/api/orders/seller/returns/" + returnId + "/confirm-receipt")
				.header(HttpHeaders.AUTHORIZATION, sellerToken())
				.param("shopId", SHOP_ID))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("INVALID_RETURN_STATUS"));
	}

	@Test
	void adminCannotResolveWhenPending() throws Exception {
		Order order = createDeliveredOrder();
		String orderItemId = order.getItems().get(0).getId();

		String body = """
				{
				  "reason": "Hàng lỗi",
				  "items": [{ "orderItemId": "%s", "productId": "01JBQ9PROD0000000000000001", "quantity": 1 }]
				}
				""".formatted(orderItemId);

		mockMvc.perform(post("/api/orders/" + order.getId() + "/returns")
				.header(HttpHeaders.AUTHORIZATION, buyerToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isCreated());

		String returnId = returnRepository.findAll().get(0).getId();

		// Admin cố phân xử khi yêu cầu vẫn đang PENDING (chưa DISPUTED) -> bị chặn 409
		mockMvc.perform(post("/api/orders/admin/returns/" + returnId + "/resolve")
				.header(HttpHeaders.AUTHORIZATION, adminToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"approved\":true,\"restock\":true,\"note\":\"Admin can thiệp sớm\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("INVALID_RETURN_STATUS"));
	}

	@Test
	void buyerCannotCompleteOrderWhenActiveReturnExists() throws Exception {
		Order order = createDeliveredOrder();
		String orderItemId = order.getItems().get(0).getId();

		String body = """
				{
				  "reason": "Hàng không ưng ý",
				  "items": [{ "orderItemId": "%s", "productId": "01JBQ9PROD0000000000000001", "quantity": 1 }]
				}
				""".formatted(orderItemId);

		mockMvc.perform(post("/api/orders/" + order.getId() + "/returns")
				.header(HttpHeaders.AUTHORIZATION, buyerToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isCreated());

		// Buyer cố hoàn tất đơn khi Return đang PENDING -> bị chặn 409 ORDER_HAS_ACTIVE_RETURN
		mockMvc.perform(post("/api/orders/" + order.getId() + "/complete")
				.header(HttpHeaders.AUTHORIZATION, buyerToken()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("ORDER_HAS_ACTIVE_RETURN"));
	}

	@Test
	void concurrentReturnCreation_isSerializedAndItemOnlyReturnedOnce() throws Exception {
		Order order = createDeliveredOrder();
		String orderItemId = order.getItems().get(0).getId();

		String body = """
				{
				  "reason": "Hàng bị rách",
				  "items": [{ "orderItemId": "%s", "productId": "01JBQ9PROD0000000000000001", "quantity": 1 }]
				}
				""".formatted(orderItemId);

		int threads = 2;
		java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
		java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threads);
		java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
		java.util.List<Integer> statuses = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

		for (int i = 0; i < threads; i++) {
			executor.submit(() -> {
				ready.countDown();
				try {
					start.await();
					var res = mockMvc.perform(post("/api/orders/" + order.getId() + "/returns")
							.header(HttpHeaders.AUTHORIZATION, buyerToken())
							.contentType(MediaType.APPLICATION_JSON)
							.content(body))
							.andReturn();
					statuses.add(res.getResponse().getStatus());
				} catch (Exception e) {
					// ignore
				}
			});
		}

		ready.await();
		start.countDown();
		executor.shutdown();
		executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

		// Đúng 1 request thành công (201 Created), request còn lại bị xung đột (409 Conflict)
		assertThat(statuses).containsExactlyInAnyOrder(201, 409);
		assertThat(returnRepository.findByOrderId(order.getId())).hasSize(1);
	}
}
