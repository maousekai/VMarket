package com.vmarket.cart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.vmarket.cart.CartLimits;
import com.vmarket.cart.config.CartProperties;
import com.vmarket.cart.dto.AddCartItemRequest;
import com.vmarket.cart.dto.CartResponse;
import com.vmarket.cart.exception.ApiException;

import tools.jackson.databind.json.JsonMapper;

/**
 * Test nghiệp vụ giỏ hàng với Redis thay bằng Map trong bộ nhớ — kiểm tra
 * logic gộp item, nhóm theo shop, cập nhật/xoá và TTL.
 */
class CartServiceTest {

	private Map<String, String> store;
	private StringRedisTemplate redis;
	private ValueOperations<String, String> ops;
	private CartService cartService;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		store = new HashMap<>();
		redis = mock(StringRedisTemplate.class);
		ops = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(ops);
		doAnswer(inv -> store.put(inv.getArgument(0), inv.getArgument(1)))
				.when(ops).set(anyString(), anyString(), any(Duration.class));
		when(ops.get(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0)));
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
	void addItemStoresDocumentWithTtlInOneCommand() {
		cartService.addItem("user-1", add("p1", null, "shop-1", 2, "100000"));

		assertThat(store).containsKey("cart:user-1");
		// SET kèm TTL trong MỘT lệnh. Trước đây là SET rồi EXPIRE rời: hỏng giữa hai
		// lệnh thì key sống vĩnh viễn, còn lần ghi đè thì xoá TTL cũ trước khi lệnh
		// EXPIRE kịp chạy (P2 của review PR #23).
		verify(ops).set(eq("cart:user-1"), anyString(), eq(Duration.ofDays(30)));
		verify(redis, never()).expire(anyString(), any(Duration.class));
	}

	/**
	 * Kịch bản của review: dữ liệu cũ có {@code quantity = Integer.MAX_VALUE}, cộng
	 * thêm 1 bằng {@code int} sẽ thành số ÂM (tổng tiền âm, kiểm tra tồn kho luôn
	 * "đủ hàng"). Nay phải bị chặn với 400 và KHÔNG ghi đè gì.
	 */
	@Test
	void addItem_congDonVuotTranInt_tra400_vaKhongTaoSoLuongAm() throws Exception {
		JsonMapper mapper = JsonMapper.builder().build();
		store.put("cart:user-1", mapper.writeValueAsString(new CartService.CartDocument(
				List.of(new CartService.StoredItem("p1", null, "shop-1", Integer.MAX_VALUE,
						new BigDecimal("100000"), Instant.now(), Instant.now())))));

		assertThatThrownBy(() -> cartService.addItem("user-1", add("p1", null, "shop-1", 1, "100000")))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("code", "CART_QUANTITY_LIMIT_EXCEEDED");

		// Dữ liệu trong Redis giữ nguyên — không có số lượng âm nào được ghi ra.
		assertThat(store.get("cart:user-1")).contains(String.valueOf(Integer.MAX_VALUE));
	}

	@Test
	void addItem_congDonDungBangTran_thiVanChoPhep() {
		cartService.addItem("user-1", add("p1", null, "shop-1", CartLimits.MAX_QUANTITY_PER_ITEM - 1, "1000"));
		CartResponse response = cartService.addItem("user-1", add("p1", null, "shop-1", 1, "1000"));

		assertThat(response.totalQuantity()).isEqualTo(CartLimits.MAX_QUANTITY_PER_ITEM);
	}

	@Test
	void addItem_duTranSoDongHang_tra400() {
		for (int i = 0; i < CartLimits.MAX_ITEMS_PER_CART; i++) {
			cartService.addItem("user-1", add("p" + i, null, "shop-1", 1, "1000"));
		}

		assertThatThrownBy(() -> cartService.addItem("user-1", add("p-cuoi", null, "shop-1", 1, "1000")))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("code", "CART_ITEM_LIMIT_EXCEEDED");
	}

	@Test
	void updateQuantity_ngoaiBien_tra400() {
		cartService.addItem("user-1", add("p1", null, "shop-1", 1, "1000"));

		assertThatThrownBy(() -> cartService.updateQuantity("user-1", "p1", null,
				CartLimits.MAX_QUANTITY_PER_ITEM + 1))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("code", "CART_QUANTITY_LIMIT_EXCEEDED");
		assertThatThrownBy(() -> cartService.updateQuantity("user-1", "p1", null, 0))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("code", "CART_QUANTITY_LIMIT_EXCEEDED");
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
