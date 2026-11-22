package com.vmarket.cart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.vmarket.cart.CartLimits;
import com.vmarket.cart.config.CartProperties;
import com.vmarket.cart.dto.AddCartItemRequest;
import com.vmarket.cart.dto.CartResponse;
import com.vmarket.cart.exception.ApiException;
import com.vmarket.cart.exception.CartStorageException;

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
	private FakeProductCatalog catalog;

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

		catalog = new FakeProductCatalog();
		cartService = new CartService(redis, JsonMapper.builder().build(), new CartProperties(), catalog);
	}

	private AddCartItemRequest add(String productId, String variantId, String shopId,
			int quantity, String price) {
		catalog.list(productId, variantId, shopId, new BigDecimal(price));
		return new AddCartItemRequest(productId, variantId, shopId, quantity);
	}

	@Test
	void emptyCartReturnsEmptyGroupsAndZeroTotal() {
		CartResponse response = cartService.getCart("user-1");

		assertThat(response.groups()).isEmpty();
		assertThat(response.totalQuantity()).isZero();
		assertThat(response.totalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	/** PBL6-48: đơn giá và gian hàng lấy từ product-service, không từ request. */
	@Test
	void addItemUsesCatalogPriceAndShop() {
		catalog.list("p1", null, "shop-that", new BigDecimal("99000"));

		CartResponse response = cartService.addItem("user-1", new AddCartItemRequest("p1", null, "shop-gia", 2));

		assertThat(response.groups()).singleElement().satisfies(group -> {
			assertThat(group.shopId()).isEqualTo("shop-that");
			assertThat(group.items().get(0).unitPrice()).isEqualByComparingTo("99000");
		});
		assertThat(response.totalAmount()).isEqualByComparingTo("198000");
	}

	@Test
	void addItemUsesVariantPrice() {
		catalog.list("p1", "v-red", "shop-1", new BigDecimal("120000"));
		catalog.list("p1", "v-blue", "shop-1", new BigDecimal("130000"));

		CartResponse response = cartService.addItem("user-1", new AddCartItemRequest("p1", "v-blue", "shop-1", 1));

		assertThat(response.totalAmount()).isEqualByComparingTo("130000");
	}

	@Test
	void addItemRejectsUnknownProductOrVariant() {
		catalog.list("p1", "v-red", "shop-1", new BigDecimal("120000"));

		assertThatThrownBy(() -> cartService.addItem("user-1", new AddCartItemRequest("p-khong-co", null, "shop-1", 1)))
				.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo("PRODUCT_NOT_FOUND"));
		assertThatThrownBy(() -> cartService.addItem("user-1", new AddCartItemRequest("p1", "v-khong-co", "shop-1", 1)))
				.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo("PRODUCT_NOT_FOUND"));
		// Sản phẩm nhiều biến thể mà không chọn biến thể
		catalog.list("p1", "v-blue", "shop-1", new BigDecimal("130000"));
		assertThatThrownBy(() -> cartService.addItem("user-1", new AddCartItemRequest("p1", null, "shop-1", 1)))
				.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo("VARIANT_REQUIRED"));
		assertThat(store).doesNotContainKey("cart:user-1");
	}

	@Test
	void addItemWhenCatalogDownReturns503AndStoresNothing() {
		catalog.list("p1", null, "shop-1", new BigDecimal("1000"));
		catalog.down = true;

		assertThatThrownBy(() -> cartService.addItem("user-1", new AddCartItemRequest("p1", null, "shop-1", 1)))
				.isInstanceOfSatisfying(ApiException.class, ex -> {
					assertThat(ex.getCode()).isEqualTo("PRODUCT_CATALOG_UNAVAILABLE");
					assertThat(ex.getStatus().value()).isEqualTo(503);
				});
		assertThat(store).doesNotContainKey("cart:user-1");
	}

	/** Thiếu shopId từ product-service: không lấy shopId của request, trả 503. */
	@Test
	void addItemWithoutCatalogShopReturns503() {
		catalog.list("p1", null, null, new BigDecimal("1000"));

		assertThatThrownBy(() -> cartService.addItem("user-1", new AddCartItemRequest("p1", null, "shop-gia", 1)))
				.isInstanceOfSatisfying(ApiException.class,
						ex -> assertThat(ex.getCode()).isEqualTo("PRODUCT_CATALOG_UNAVAILABLE"));
		assertThat(store).doesNotContainKey("cart:user-1");
	}

	/** Giá 0: order-service không bán được, nên cart cũng không nhận vào giỏ. */
	@Test
	void addItemWithZeroCatalogPriceIsRejected() {
		catalog.list("p1", null, "shop-1", BigDecimal.ZERO);

		assertThatThrownBy(() -> cartService.addItem("user-1", new AddCartItemRequest("p1", null, "shop-1", 1)))
				.isInstanceOfSatisfying(ApiException.class,
						ex -> assertThat(ex.getCode()).isEqualTo("PRODUCT_CATALOG_UNAVAILABLE"));
		assertThat(store).doesNotContainKey("cart:user-1");
	}

	@Test
	void refreshShopMovesOnlyThatLineToTheRealShop() {
		cartService.addItem("user-1", add("p1", null, "shop-1", 2, "100000"));
		cartService.addItem("user-1", add("p2", null, "shop-1", 1, "50000"));

		cartService.refreshShop("user-1", "p1", null, "shop-2");

		assertThat(cartService.items("user-1"))
				.extracting(CartService.StoredItem::productId, CartService.StoredItem::shopId)
				.containsExactlyInAnyOrder(
						org.assertj.core.groups.Tuple.tuple("p1", "shop-2"),
						org.assertj.core.groups.Tuple.tuple("p2", "shop-1"));
	}

	@Test
	void refreshPriceUpdatesOnlyThatLine() {
		cartService.addItem("user-1", add("p1", null, "shop-1", 2, "100000"));
		cartService.addItem("user-1", add("p2", null, "shop-1", 1, "50000"));

		cartService.refreshPrice("user-1", "p1", null, new BigDecimal("110000"));

		CartResponse response = cartService.getCart("user-1");
		assertThat(response.totalAmount()).isEqualByComparingTo("270000");
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

	@ParameterizedTest(name = "Redis unavailable during {0}")
	@ValueSource(strings = {"read", "write", "delete"})
	void redisFailurePreservesStoredCartAndRetainsCause(String operation) {
		cartService.addItem("user-1", add("p1", "red", "shop-1", 2, "100000"));
		var before = Map.copyOf(store);
		var failure = new RedisConnectionFailureException("unit Redis unavailable");
		switch (operation) {
			case "read" -> when(ops.get("cart:user-1")).thenThrow(failure);
			case "write" -> doThrow(failure).when(ops)
					.set(anyString(), anyString(), any(Duration.class));
			case "delete" -> doThrow(failure).when(redis).delete("cart:user-1");
			default -> throw new IllegalArgumentException(operation);
		}
		clearInvocations(redis, ops);

		assertThatThrownBy(() -> {
			switch (operation) {
				case "read" -> cartService.getCart("user-1");
				case "write" -> cartService.addItem("user-1", add("p1", "red", "shop-1", 1, "100000"));
				case "delete" -> cartService.clearCart("user-1");
				default -> throw new IllegalArgumentException(operation);
			}
		}).isInstanceOf(CartStorageException.class).hasCause(failure);
		assertThat(store).isEqualTo(before);
		if (!operation.equals("write")) {
			verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
		}
		verify(redis, never()).expire(anyString(), any(Duration.class));
	}

	@Test
	void corruptJsonIsReadAsEmptyWithoutWritingAndNextAddRecovers() {
		store.put("cart:user-1", "{invalid-json");
		var before = Map.copyOf(store);
		clearInvocations(ops);

		var empty = cartService.getCart("user-1");

		assertThat(empty.groups()).isEmpty();
		assertThat(empty.totalQuantity()).isZero();
		assertThat(empty.totalAmount()).isEqualByComparingTo("0");
		assertThat(store).isEqualTo(before);
		verify(ops).get("cart:user-1");
		verifyNoMoreInteractions(ops);

		cartService.addItem("user-1", add("p1", "red", "shop-1", 2, "100000"));
		assertThat(cartService.items("user-1")).singleElement().satisfies(item -> {
			assertThat(item.productId()).isEqualTo("p1");
			assertThat(item.variantId()).isEqualTo("red");
			assertThat(item.quantity()).isEqualTo(2);
		});
		assertThat(cartService.getCart("user-1").totalAmount()).isEqualByComparingTo("200000");
	}

	@Test
	void concurrentAddsInOneServiceInstanceDoNotLoseQuantities() throws Exception {
		cartService.addItem("user-1", add("p1", "red", "shop-1", 1, "100000"));
		var start = new CyclicBarrier(2);
		var executor = Executors.newFixedThreadPool(2);
		Callable<Void> addItems = () -> {
			for (int i = 0; i < 40; i++) {
				start.await(5, TimeUnit.SECONDS);
				cartService.addItem("user-1", add("p1", "red", "shop-1", 1, "100000"));
			}
			return null;
		};
		try {
			var first = executor.submit(addItems);
			var second = executor.submit(addItems);
			first.get(15, TimeUnit.SECONDS);
			second.get(15, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}

		var response = cartService.getCart("user-1");
		assertThat(response.totalQuantity()).isEqualTo(81);
		assertThat(response.groups()).singleElement().satisfies(group ->
				assertThat(group.items()).singleElement().satisfies(item ->
						assertThat(item.quantity()).isEqualTo(81)));
		assertThat(response.totalAmount()).isEqualByComparingTo("8100000");
	}
}
