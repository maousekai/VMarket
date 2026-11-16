package com.vmarket.cart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.vmarket.cart.config.CartProperties;
import com.vmarket.cart.dto.AddCartItemRequest;
import com.vmarket.cart.dto.CartGroupDto;
import com.vmarket.cart.exception.ApiException;

import tools.jackson.databind.json.JsonMapper;

class TV01CartSheetTest {

	private static final String BUYER = "user-1";
	private static final String PRODUCT = "product-1";
	private static final String VARIANT = "variant-1";
	private static final String SHOP = "shop-1";
	private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
	private final JsonMapper mapper = JsonMapper.builder().build();
	private Map<String, String> store;
	private StringRedisTemplate redis;
	private ValueOperations<String, String> ops;
	private CartService service;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		store = new HashMap<>();
		redis = mock(StringRedisTemplate.class);
		ops = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(ops);
		when(ops.get(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0)));
		doAnswer(inv -> store.put(inv.getArgument(0), inv.getArgument(1)))
				.when(ops).set(anyString(), anyString(), any(Duration.class));
		service = new CartService(redis, mapper, new CartProperties());
	}

	@AfterEach
	void recordStoredState() {
		System.out.println("cart.fixture.after=" + store);
	}

	@Test
	@DisplayName("UT-CART-01 — Giỏ rỗng")
	void ut_CART_01() {
		var response = service.getCart(BUYER);
		assertThat(response.groups()).isEmpty();
		assertThat(response.totalQuantity()).isZero();
		assertThat(response.totalAmount()).isEqualByComparingTo("0");
		assertThat(store).isEmpty();
		verify(ops).get("cart:" + BUYER);
		verifyNoMoreInteractions(ops);
		verify(redis).opsForValue();
		verifyNoMoreInteractions(redis);
	}

	@Test
	@DisplayName("UT-CART-02 — Thêm mới và TTL")
	void ut_CART_02() {
		service.addItem(BUYER, add(PRODUCT, VARIANT, SHOP, 2, "100000"));
		var response = service.getCart(BUYER);
		assertThat(response.groups()).hasSize(1);
		var group = response.groups().get(0);
		assertThat(group.shopId()).isEqualTo(SHOP);
		assertThat(group.items()).singleElement().satisfies(item -> {
			assertThat(item.productId()).isEqualTo(PRODUCT);
			assertThat(item.variantId()).isEqualTo(VARIANT);
			assertThat(item.quantity()).isEqualTo(2);
			assertThat(item.lineTotal()).isEqualByComparingTo("200000");
		});
		assertThat(group.subtotal()).isEqualByComparingTo("200000");
		assertThat(response.totalQuantity()).isEqualTo(2);
		assertThat(response.totalAmount()).isEqualByComparingTo("200000");
		verify(ops).set(eq("cart:" + BUYER), anyString(), eq(Duration.ofDays(30)));
		verify(ops, org.mockito.Mockito.times(2)).get("cart:" + BUYER);
		verifyNoMoreInteractions(ops);
		verify(redis, org.mockito.Mockito.times(3)).opsForValue();
		verifyNoMoreInteractions(redis);
	}

	@Test
	@DisplayName("UT-CART-03 — Cộng cùng variant")
	void ut_CART_03() {
		var before = service.addItem(BUYER, add(PRODUCT, VARIANT, SHOP, 2, "100000"));
		var addedAt = before.groups().get(0).items().get(0).addedAt();
		var response = service.addItem(BUYER, add(PRODUCT, VARIANT, SHOP, 3, "110000"));
		assertThat(response.groups()).hasSize(1);
		assertThat(response.groups().get(0).items()).singleElement().satisfies(item -> {
			assertThat(item.quantity()).isEqualTo(5);
			assertThat(item.unitPrice()).isEqualByComparingTo("110000");
			assertThat(item.lineTotal()).isEqualByComparingTo("550000");
			assertThat(item.addedAt()).isEqualTo(addedAt);
		});
		assertThat(response.totalQuantity()).isEqualTo(5);
		assertThat(response.totalAmount()).isEqualByComparingTo("550000");
	}

	@Test
	@DisplayName("UT-CART-04 — Tách biến thể")
	void ut_CART_04() {
		service.addItem(BUYER, add(PRODUCT, VARIANT, SHOP, 2, "100000"));
		var response = service.addItem(BUYER, add(PRODUCT, "variant-2", SHOP, 1, "120000"));
		assertThat(response.groups()).singleElement().satisfies(group -> {
			assertThat(group.shopId()).isEqualTo(SHOP);
			assertThat(group.items()).hasSize(2);
			assertThat(group.items()).extracting(item -> item.variantId())
					.containsExactlyInAnyOrder(VARIANT, "variant-2");
			assertThat(group.items()).filteredOn(item -> item.variantId().equals(VARIANT))
					.singleElement().satisfies(item -> assertThat(item.quantity()).isEqualTo(2));
			assertThat(group.items()).filteredOn(item -> item.variantId().equals("variant-2"))
					.singleElement().satisfies(item -> assertThat(item.quantity()).isEqualTo(1));
		});
		assertThat(response.totalQuantity()).isEqualTo(3);
		assertThat(response.totalAmount()).isEqualByComparingTo("320000");
	}

	@Test
	@DisplayName("UT-CART-05 — Tách nhóm shop và số tiền")
	void ut_CART_05() {
		service.addItem(BUYER, add(PRODUCT, VARIANT, SHOP, 2, "100000"));
		service.addItem(BUYER, add("product-2", "variant-3", "shop-2", 3, "50000"));
		var response = service.getCart(BUYER);
		assertThat(response.groups()).hasSize(2);
		var groups = response.groups().stream().collect(java.util.stream.Collectors.toMap(
				CartGroupDto::shopId, CartGroupDto::subtotal));
		assertThat(groups.get(SHOP)).isEqualByComparingTo("200000");
		assertThat(groups.get("shop-2")).isEqualByComparingTo("150000");
		assertThat(response.totalQuantity()).isEqualTo(5);
		assertThat(response.totalAmount()).isEqualByComparingTo("350000");
	}

	@ParameterizedTest(name = "UT-CART-06 seed={0} add=1")
	@ValueSource(ints = {998, 999})
	@DisplayName("UT-CART-06 — Biên cộng dồn 999")
	void ut_CART_06(int quantity) {
		seed(List.of(stored(PRODUCT, VARIANT, quantity)));
		var before = Map.copyOf(store);
		clearInvocations(ops, redis);
		if (quantity == 998) {
			var response = service.addItem(BUYER, add(PRODUCT, VARIANT, SHOP, 1, "100000"));
			assertThat(response.totalQuantity()).isEqualTo(999);
			assertThat(response.groups().get(0).items()).singleElement()
					.satisfies(item -> assertThat(item.quantity()).isEqualTo(999));
		} else {
			assertRejectedWithoutWrite(before, () -> service.addItem(BUYER,
					add(PRODUCT, VARIANT, SHOP, 1, "100000")), "CART_QUANTITY_LIMIT_EXCEEDED");
		}
	}

	@Test
	@DisplayName("UT-CART-07 — Biên 50 dòng")
	void ut_CART_07() {
		seed(IntStream.rangeClosed(1, 49).mapToObj(i -> stored(limitProduct(i), limitVariant(i), 1)).toList());
		var accepted = service.addItem(BUYER, add(limitProduct(50), limitVariant(50), SHOP, 1, "100000"));
		assertThat(accepted.groups().get(0).items()).hasSize(50);
		assertThat(accepted.totalQuantity()).isEqualTo(50);
		var before = Map.copyOf(store);
		clearInvocations(ops, redis);
		assertRejectedWithoutWrite(before, () -> service.addItem(BUYER,
				add(limitProduct(51), limitVariant(51), SHOP, 1, "100000")), "CART_ITEM_LIMIT_EXCEEDED");
		assertThat(service.getCart(BUYER).groups().get(0).items()).hasSize(50);
		var merged = service.addItem(BUYER, add(limitProduct(1), limitVariant(1), SHOP, 1, "100000"));
		assertThat(merged.groups().get(0).items()).hasSize(50);
		assertThat(merged.totalQuantity()).isEqualTo(51);
		assertThat(merged.groups().get(0).items()).filteredOn(item -> item.productId().equals(limitProduct(1)))
				.singleElement().satisfies(item -> assertThat(item.quantity()).isEqualTo(2));
	}

	@Test
	@DisplayName("UT-CART-08 — Chống tràn số legacy")
	void ut_CART_08() {
		seed(List.of(stored(PRODUCT, VARIANT, Integer.MAX_VALUE)));
		var before = Map.copyOf(store);
		clearInvocations(ops, redis);
		assertRejectedWithoutWrite(before, () -> service.addItem(BUYER,
				add(PRODUCT, VARIANT, SHOP, 1, "100000")), "CART_QUANTITY_LIMIT_EXCEEDED");
	}

	private void assertRejectedWithoutWrite(Map<String, String> before, Runnable action, String code) {
		ApiException error = catchThrowableOfType(ApiException.class, action::run);
		assertThat(error).isNotNull();
		assertThat(error.getCode()).isEqualTo(code);
		assertThat(error.getStatus().value()).isEqualTo(400);
		assertThat(store).isEqualTo(before);
		verify(ops).get("cart:" + BUYER);
		verifyNoMoreInteractions(ops);
		verify(redis).opsForValue();
		verifyNoMoreInteractions(redis);
	}

	private void seed(List<CartService.StoredItem> items) {
		store.put("cart:" + BUYER, mapper.writeValueAsString(new CartService.CartDocument(items)));
		System.out.println("cart.fixture.before=" + store);
	}

	private static CartService.StoredItem stored(String product, String variant, int quantity) {
		return new CartService.StoredItem(product, variant, SHOP, quantity, new BigDecimal("100000"), T0, T0);
	}

	private static AddCartItemRequest add(String product, String variant, String shop, int qty, String price) {
		return new AddCartItemRequest(product, variant, shop, qty, new BigDecimal(price));
	}

	private static String limitProduct(int i) { return "product-limit-%03d".formatted(i); }
	private static String limitVariant(int i) { return "variant-limit-%03d".formatted(i); }
}
