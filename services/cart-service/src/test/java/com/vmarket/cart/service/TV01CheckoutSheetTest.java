package com.vmarket.cart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.vmarket.cart.dto.CheckoutCheckResponse;
import com.vmarket.cart.dto.CheckoutIssueDto;
import com.vmarket.cart.dto.ProductSnapshot;
import com.vmarket.cart.dto.ProductSnapshot.VariantSnapshot;
import com.vmarket.cart.exception.ProductCatalogUnavailableException;

class TV01CheckoutSheetTest {

	private static final String BUYER = "user-1";
	private static final String PRODUCT = "product-1";
	private static final String VARIANT = "variant-1";
	private static final String SHOP = "shop-1";
	private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
	private CartService cart;
	private ProductCatalogClient catalog;
	private CheckoutService service;
	private List<CartService.StoredItem> items;
	private List<CartService.StoredItem> before;

	@BeforeEach
	void setUp() {
		cart = mock(CartService.class);
		catalog = mock(ProductCatalogClient.class);
		service = new CheckoutService(cart, catalog);
	}

	@AfterEach
	void cartRemainsUnchanged() {
		assertThat(items).containsExactlyElementsOf(before);
		verify(cart).items(BUYER);
		verifyNoMoreInteractions(cart);
	}

	@Test
	@DisplayName("UT-CHK-01 — Giỏ rỗng")
	void ut_CHK_01() {
		givenItems(List.of());
		assertIssues("CART_EMPTY");
		verifyNoInteractions(catalog);
	}

	@Test
	@DisplayName("UT-CHK-02 — Đủ tồn đúng biên")
	void ut_CHK_02() {
		givenDefaultItem(2);
		givenVariant("100000", 2, SHOP);
		var response = validate();
		assertThat(response.ok()).isTrue();
		assertThat(response.issues()).isEmpty();
		verify(catalog).findProduct(PRODUCT);
		verifyNoMoreInteractions(catalog);
	}

	@Test
	@DisplayName("UT-CHK-03 — Giá khác nhưng còn hàng")
	void ut_CHK_03() {
		givenDefaultItem(2);
		givenVariant("110000", 10, SHOP);
		var response = assertIssues("PRICE_CHANGED");
		assertThat(response.issues().get(0).cartPrice()).isEqualByComparingTo("100000");
		assertThat(response.issues().get(0).currentPrice()).isEqualByComparingTo("110000");
	}

	@Test
	@DisplayName("UT-CHK-04 — Thiếu tồn một đơn vị")
	void ut_CHK_04() {
		givenDefaultItem(2);
		givenVariant("100000", 1, SHOP);
		var response = assertIssues("OUT_OF_STOCK");
		assertThat(response.issues().get(0).requestedQuantity()).isEqualTo(2);
		assertThat(response.issues().get(0).availableStock()).isEqualTo(1);
	}

	@Test
	@DisplayName("UT-CHK-05 — Đồng thời đổi giá và thiếu hàng")
	void ut_CHK_05() {
		givenDefaultItem(2);
		givenVariant("110000", 1, SHOP);
		var response = assertIssues("PRICE_CHANGED", "OUT_OF_STOCK");
		assertThat(response.issues()).allSatisfy(issue -> {
			assertThat(issue.productId()).isEqualTo(PRODUCT);
			assertThat(issue.variantId()).isEqualTo(VARIANT);
			assertThat(issue.cartPrice()).isEqualByComparingTo("100000");
			assertThat(issue.currentPrice()).isEqualByComparingTo("110000");
			assertThat(issue.requestedQuantity()).isEqualTo(2);
		});
		assertThat(response.issues()).filteredOn(issue -> issue.type().equals("OUT_OF_STOCK"))
				.singleElement().satisfies(issue -> assertThat(issue.availableStock()).isEqualTo(1));
	}

	@ParameterizedTest(name = "UT-CHK-06 missingProduct={0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("UT-CHK-06 — Product hoặc variant không còn")
	void ut_CHK_06(boolean missingProduct) {
		givenDefaultItem(2);
		when(catalog.findProduct(PRODUCT)).thenReturn(missingProduct ? Optional.empty()
				: Optional.of(snapshot(SHOP, new VariantSnapshot("variant-2", new BigDecimal("120000"), 5))));
		var response = assertIssues("NOT_FOUND");
		assertThat(response.issues().get(0).productId()).isEqualTo(PRODUCT);
		assertThat(response.issues().get(0).variantId()).isEqualTo(VARIANT);
	}

	@Test
	@DisplayName("UT-CHK-07 — Shop mismatch")
	void ut_CHK_07() {
		givenDefaultItem(2);
		givenVariant("110000", 1, "shop-2");
		assertIssues("SHOP_CHANGED");
	}

	@ParameterizedTest(name = "UT-CHK-08 missingPrice={0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("UT-CHK-08 — Dữ liệu thiếu giá hoặc stock")
	void ut_CHK_08(boolean missingPrice) {
		givenDefaultItem(missingPrice ? 2 : 1);
		givenVariant(missingPrice ? null : "100000", missingPrice ? 10 : null, SHOP);
		var response = assertIssues(missingPrice ? "PRODUCT_DATA_INVALID" : "OUT_OF_STOCK");
		if (!missingPrice) {
			assertThat(response.issues().get(0).requestedQuantity()).isEqualTo(1);
			assertThat(response.issues().get(0).availableStock()).isZero();
		}
	}

	@Test
	@DisplayName("UT-CHK-09 — Catalog mất kết nối")
	void ut_CHK_09() {
		givenItems(List.of(item(PRODUCT, VARIANT, SHOP, 2, "100000"),
				item("product-2", "variant-3", "shop-2", 3, "50000")));
		when(catalog.findProduct(PRODUCT)).thenThrow(new ProductCatalogUnavailableException("unit unavailable", null));
		var response = assertIssues("SERVICE_UNAVAILABLE");
		assertThat(response.issues().get(0).productId()).isEqualTo(PRODUCT);
		assertThat(response.issues().get(0).variantId()).isEqualTo(VARIANT);
		verify(catalog).findProduct(PRODUCT);
		verify(catalog, never()).findProduct("product-2");
		verifyNoMoreInteractions(catalog);
	}

	private CheckoutCheckResponse validate() {
		var response = service.validate(BUYER);
		System.out.println("cart.before=" + before + "; checkout.actual=" + response + "; cart.after=" + items);
		return response;
	}

	private CheckoutCheckResponse assertIssues(String... types) {
		var response = validate();
		assertThat(response.ok()).isFalse();
		assertThat(response.issues()).extracting(CheckoutIssueDto::type).containsExactlyInAnyOrder(types);
		return response;
	}

	private void givenDefaultItem(int quantity) {
		givenItems(List.of(item(PRODUCT, VARIANT, SHOP, quantity, "100000")));
	}

	private void givenItems(List<CartService.StoredItem> fixture) {
		items = new ArrayList<>(fixture);
		before = List.copyOf(items);
		when(cart.items(BUYER)).thenReturn(items);
	}

	private void givenVariant(String price, Integer stock, String shop) {
		when(catalog.findProduct(PRODUCT)).thenReturn(Optional.of(snapshot(shop,
				new VariantSnapshot(VARIANT, price == null ? null : new BigDecimal(price), stock))));
	}

	private static ProductSnapshot snapshot(String shop, VariantSnapshot variant) {
		// Deliberately different top-level values detect bypassing the variant lookup.
		return new ProductSnapshot(PRODUCT, shop, new BigDecimal("999999"), 99, List.of(variant));
	}

	private static CartService.StoredItem item(String product, String variant, String shop, int qty, String price) {
		return new CartService.StoredItem(product, variant, shop, qty, new BigDecimal(price), T0, T0);
	}
}
