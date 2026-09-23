package com.vmarket.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.vmarket.order.client.CartServiceClient;
import com.vmarket.order.client.UserServiceClient;
import com.vmarket.order.config.OrderJwtProperties;
import com.vmarket.order.entity.Order;
import com.vmarket.order.entity.OrderStatus;
import com.vmarket.order.repository.OrderRepository;

/**
 * Kiểm tra API đơn hàng qua MockMvc với token JWT thật: token hợp lệ thì nhận dữ
 * liệu; thiếu/sai token trả 401; Bob đoán đúng id cũng không thấy đơn của Alice
 * (IDOR). UserServiceClient và CartServiceClient đều mock ({@code @MockitoBean})
 * — test này chỉ kiểm tra nghiệp vụ của order-service.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderApiTest {

	private static final String SECRET = "test-only-secret-0123456789-0123456789-0123456789";

	/* ULID 26 ký tự đúng chuẩn (BaseEntity.ID_LENGTH) — dài hơn sẽ vấp @Size(26). */
	private static final String ALICE = "01JBQ9USER000000000ALICE01";
	private static final String BOB = "01JBQ9USER0000000000BOB001";
	private static final String ADDRESS_ID = "01JBQ9ADDR0000000000TEST01";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private OrderJwtProperties jwtProperties;

	@MockitoBean
	private UserServiceClient userServiceClient;

	@MockitoBean
	private CartServiceClient cartServiceClient;

	/**
	 * Dọn bảng orders sau mỗi test: H2 chạy với {@code DB_CLOSE_DELAY=-1} nên dữ
	 * liệu của test trước còn nguyên khi test sau bắt đầu — nếu không xoá thì
	 * {@code listAndDetailOnlyShowOwnOrders} đếm thấy đơn của test khác và
	 * {@code placeOrder...} không còn là giỏ "bình thường".
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
		return new UserServiceClient.AddressView(ADDRESS_ID, ALICE, "Nguyễn Văn An", "0912345678",
				"Đà Nẵng", "Hải Châu", "Thạch Thang", "54 Nguyễn Lương Bằng");
	}

	private CartServiceClient.CartView cartWithItems() {
		CartServiceClient.CartItemView item1 = new CartServiceClient.CartItemView(
				"01JBQ9PROD000000000ITEM001", null, "01JBQ9SHOP0000000000TEST01",
				2, new BigDecimal("250000.00"), new BigDecimal("500000.00"));
		CartServiceClient.CartItemView item2 = new CartServiceClient.CartItemView(
				"01JBQ9PROD000000000ITEM002", "01JBQ9VARI0000000000TEST01", "01JBQ9SHOP0000000000TEST01",
				1, new BigDecimal("250000.00"), new BigDecimal("250000.00"));
		return new CartServiceClient.CartView(ALICE,
				List.of(new CartServiceClient.CartGroupView("01JBQ9SHOP0000000000TEST01",
						List.of(item1, item2), new BigDecimal("750000.00"))),
				3, new BigDecimal("750000.00"));
	}

	@Test
	void placeOrderWithoutTokenReturns401() throws Exception {
		mockMvc.perform(post("/api/orders")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"addressId\":\"" + ADDRESS_ID + "\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}

	@Test
	void placeOrderWithWrongIssuerTokenReturns401() throws Exception {
		mockMvc.perform(post("/api/orders")
				.header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.wrongIssuerToken(SECRET, ALICE)))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"addressId\":\"" + ADDRESS_ID + "\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}

	@Test
	void placeOrderWithMissingAddressIdReturns400() throws Exception {
		mockMvc.perform(post("/api/orders")
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

		mockMvc.perform(post("/api/orders")
				.header(HttpHeaders.AUTHORIZATION, aliceToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"addressId\":\"" + ADDRESS_ID + "\",\"note\":\"Giao giờ hành chính\"}"))
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
		assertThat(saved.getItems()).extracting(i -> i.getProductId())
				.containsExactlyInAnyOrder("01JBQ9PROD000000000ITEM001", "01JBQ9PROD000000000ITEM002");
		assertThat(saved.getItems()).extracting(i -> i.getLineTotal())
				.containsExactlyInAnyOrder(new BigDecimal("500000.00"), new BigDecimal("250000.00"));

		verify(cartServiceClient).clearCart(eq(ALICE));
	}

	@Test
	void placeOrderWithEmptyCartReturns400() throws Exception {
		when(userServiceClient.getAddress(anyString(), eq(ADDRESS_ID))).thenReturn(aliceAddress());
		when(cartServiceClient.getCart(eq(ALICE))).thenReturn(
				new CartServiceClient.CartView(ALICE, List.of(), 0, BigDecimal.ZERO));

		mockMvc.perform(post("/api/orders")
				.header(HttpHeaders.AUTHORIZATION, aliceToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"addressId\":\"" + ADDRESS_ID + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("CART_EMPTY"));

		// Không đặt được thì không xoá giỏ, không ghi đơn.
		verify(cartServiceClient, never()).clearCart(anyString());
		assertThat(orderRepository.findByUserIdOrderByCreatedAtDesc(ALICE)).isEmpty();
	}

	@Test
	void placeOrderWithForeignAddressReturns404() throws Exception {
		// user-service trả địa chỉ của người KHÁC — order-service phải tự chặn thay
		// vì ghi đơn sai chủ (IDOR ở đầu vào).
		when(userServiceClient.getAddress(anyString(), eq(ADDRESS_ID))).thenReturn(
				new UserServiceClient.AddressView(ADDRESS_ID, BOB, "Bob", "0912345678",
						"Đà Nẵng", "Hải Châu", "Thạch Thang", "54 Nguyễn Lương Bằng"));

		mockMvc.perform(post("/api/orders")
				.header(HttpHeaders.AUTHORIZATION, aliceToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"addressId\":\"" + ADDRESS_ID + "\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("ADDRESS_NOT_FOUND"));

		assertThat(orderRepository.findByUserIdOrderByCreatedAtDesc(ALICE)).isEmpty();
	}

	@Test
	void listAndDetailOnlyShowOwnOrders() throws Exception {
		createOrderForAlice();

		// Alice thấy đơn của mình.
		mockMvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, aliceToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].status").value("PENDING"));

		// Bob đoán đúng id cũng không đọc được (IDOR).
		String orderId = orderRepository.findByUserIdOrderByCreatedAtDesc(ALICE).get(0).getId();
		mockMvc.perform(get("/api/orders/" + orderId).header(HttpHeaders.AUTHORIZATION, bobToken()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
	}

	@Test
	void cancelPendingOrderSucceeds() throws Exception {
		createOrderForAlice();
		String orderId = orderRepository.findByUserIdOrderByCreatedAtDesc(ALICE).get(0).getId();

		mockMvc.perform(put("/api/orders/" + orderId + "/cancel")
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

		mockMvc.perform(put("/api/orders/" + processing.getId() + "/cancel")
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
		when(cartServiceClient.clearCart(eq(ALICE))).thenReturn(true);
		mockMvc.perform(post("/api/orders")
				.header(HttpHeaders.AUTHORIZATION, aliceToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"addressId\":\"" + ADDRESS_ID + "\"}"))
				.andExpect(status().isCreated());
	}
}