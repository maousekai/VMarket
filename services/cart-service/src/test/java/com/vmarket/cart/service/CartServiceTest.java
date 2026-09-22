package com.vmarket.cart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.vmarket.cart.config.CartProperties;
import com.vmarket.cart.dto.AddCartItemRequest;
import com.vmarket.cart.dto.CartResponse;

import tools.jackson.databind.json.JsonMapper;

/**
 * Test nghiệp vụ giỏ hàng với Redis thay bằng Map trong bộ nhớ — kiểm tra
 * logic gộp item, nhóm theo shop, cập nhật/xoá và TTL.
 */
class CartServiceTest {

	private Map<String, String> store;
	private StringRedisTemplate redis;
	private CartService cartService;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		store = new HashMap<>();
		redis = mock(StringRedisTemplate.class);
		ValueOperations<String, String> ops = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(ops);
		doAnswer(inv -> store.put(inv.getArgument(0), inv.getArgument(1)))
				.when(ops).set(anyString(), anyString());
		when(ops.get(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0)));
		when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);
		when(redis.delete(anyString())).thenAnswer(inv -> store.remove(inv.getArgument(0)) != null);

		cartService = new CartService(redis, JsonMapper.builder().build(), new CartProperties());
	}

	private static AddCartItemRequest add(String productId, String variantId, String shopId,
			int quantity, String price) {
		return new AddCartItemRequest(productId, variantId, shopId, quantity, new BigDecimal(price));
	}

	@Test
	void emptyCartReturnsEmptyGroupsAndZeroTotal() {
		CartResponse response = cartService.getCart("user-1");

		assertThat(response.groups()).isEmpty();
		assertThat(response.totalQuantity()).isZero();
		assertThat(response.totalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void addItemStoresDocumentAndRefreshesTtl() {
		cartService.addItem("user-1", add("p1", null, "shop-1", 2, "100000"));

		assertThat(store).containsKey("cart:user-1");
		// TTL được refresh sau khi ghi (FR-CART-01: đồng bộ giữa web/mobile)
		verify(redis).expire(anyString(), any(Duration.class));
	}

	@Test
	void addItemMergesSameProductAndVariant() {
		cartService.addItem("user-1", add("p1", null, "shop-1", 2, "100000"));
		CartResponse response = cartService.addItem("user-1", add("p1", null, "shop-1", 1, "120000"));

		assertThat(response.totalQuantity()).isEqualTo(3);
		assertThat(response.groups()).hasSize(1);
		// Giá snapshot được cập nhật theo lần thêm gần nhất
		assertThat(response.groups().get(0).items().get(0).unitPrice())
				.isEqualByComparingTo(new BigDecimal("120000"));
	}

	@Test
	void addDifferentVariantsKeepsSeparateItems() {
		cartService.addItem("user-1", add("p1", "red", "shop-1", 1, "100000"));
		CartResponse response = cartService.addItem("user-1", add("p1", "blue", "shop-1", 1, "110000"));

		assertThat(response.totalQuantity()).isEqualTo(2);
		assertThat(response.groups().get(0).items()).hasSize(2);
	}

	@Test
	void cartIsGroupedByShopWithSubtotals() {
		cartService.addItem("user-1", add("p1", null, "shop-A", 1, "100000"));
		cartService.addItem("user-1", add("p2", null, "shop-A", 2, "50000"));
		cartService.addItem("user-1", add("p3", null, "shop-B", 1, "30000"));

		CartResponse response = cartService.getCart("user-1");

		assertThat(response.groups()).hasSize(2);
		assertThat(response.groups().get(0).shopId()).isEqualTo("shop-A");
		assertThat(response.groups().get(0).subtotal()).isEqualByComparingTo(new BigDecimal("200000"));
		assertThat(response.groups().get(1).subtotal()).isEqualByComparingTo(new BigDecimal("30000"));
		assertThat(response.totalAmount()).isEqualByComparingTo(new BigDecimal("230000"));
		assertThat(response.totalQuantity()).isEqualTo(4);
	}

	@Test
	void updateQuantityChangesLineTotal() {
		cartService.addItem("user-1", add("p1", null, "shop-1", 1, "100000"));

		CartResponse response = cartService.updateQuantity("user-1", "p1", null, 5);

		assertThat(response.totalQuantity()).isEqualTo(5);
		assertThat(response.totalAmount()).isEqualByComparingTo(new BigDecimal("500000"));
	}

	@Test
	void removeItemDeletesOnlyThatItem() {
		cartService.addItem("user-1", add("p1", "red", "shop-1", 1, "100000"));
		cartService.addItem("user-1", add("p1", "blue", "shop-1", 1, "100000"));

		CartResponse response = cartService.removeItem("user-1", "p1", "red");

		assertThat(response.totalQuantity()).isEqualTo(1);
		assertThat(response.groups().get(0).items().get(0).variantId()).isEqualTo("blue");
	}

	@Test
	void clearCartRemovesRedisKey() {
		cartService.addItem("user-1", add("p1", null, "shop-1", 1, "100000"));
		cartService.clearCart("user-1");

		assertThat(store).doesNotContainKey("cart:user-1");
		assertThat(cartService.getCart("user-1").groups()).isEmpty();
	}

	@Test
	void storedJsonRoundTripsThroughObjectMapper() {
		cartService.addItem("user-1", add("p1", "red", "shop-1", 2, "123456.78"));

		String json = store.get("cart:user-1");
		assertThat(json).contains("\"productId\":\"p1\"").contains("123456.78");

		// Đọc lại qua service (decode từ JSON) — tổng đúng đến xu
		assertThat(cartService.getCart("user-1").totalAmount())
				.isEqualByComparingTo(new BigDecimal("246913.56"));
	}
}
