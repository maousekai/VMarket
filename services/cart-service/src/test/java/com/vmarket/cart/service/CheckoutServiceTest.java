package com.vmarket.cart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vmarket.cart.dto.CheckoutCheckResponse;
import com.vmarket.cart.dto.CheckoutIssueDto;
import com.vmarket.cart.dto.ProductSnapshot;

/**
 * Test logic kiểm tra trước thanh toán (FR-CART-03) với product-service thay
 * bằng mock — bắt cả trường hợp dịch vụ không khả dụng.
 */
class CheckoutServiceTest {

	private CartService cartService;
	private ProductCatalogClient catalog;
	private CheckoutService checkoutService;

	@BeforeEach
	void setUp() {
		cartService = mock(CartService.class);
		catalog = mock(ProductCatalogClient.class);
		checkoutService = new CheckoutService(cartService, catalog);
	}

	private static CartService.StoredItem item(String productId, String variantId,
			int quantity, String price) {
		return new CartService.StoredItem(productId, variantId, "shop-1", quantity,
				new BigDecimal(price), java.time.Instant.now(), java.time.Instant.now());
	}

	@Test
	void emptyCartCannotCheckout() {
		when(cartService.items("user-1")).thenReturn(List.of());

		CheckoutCheckResponse response = checkoutService.validate("user-1");

		assertThat(response.ok()).isFalse();
		assertThat(response.issues()).hasSize(1);
		assertThat(response.issues().get(0).type()).isEqualTo(CheckoutService.TYPE_CART_EMPTY);
	}

	@Test
	void unchangedPriceAndEnoughStockPassesValidation() {
		when(cartService.items("user-1")).thenReturn(List.of(item("p1", null, 2, "100000")));
		when(catalog.findProduct("p1")).thenReturn(Optional.of(
				new ProductSnapshot("p1", "shop-1", new BigDecimal("100000"), 10, null)));

		CheckoutCheckResponse response = checkoutService.validate("user-1");

		assertThat(response.ok()).isTrue();
		assertThat(response.issues()).isEmpty();
	}

	@Test
	void changedPriceRaisesPriceChangedIssue() {
		when(cartService.items("user-1")).thenReturn(List.of(item("p1", null, 2, "100000")));
		when(catalog.findProduct("p1")).thenReturn(Optional.of(
				new ProductSnapshot("p1", "shop-1", new BigDecimal("89000"), 10, null)));

		CheckoutCheckResponse response = checkoutService.validate("user-1");

		assertThat(response.ok()).isFalse();
		assertThat(response.issues().get(0).type()).isEqualTo(CheckoutService.TYPE_PRICE_CHANGED);
		assertThat(response.issues().get(0).currentPrice()).isEqualByComparingTo("89000");
	}

	@Test
	void lowStockRaisesOutOfStockIssue() {
		when(cartService.items("user-1")).thenReturn(List.of(item("p1", null, 5, "100000")));
		when(catalog.findProduct("p1")).thenReturn(Optional.of(
				new ProductSnapshot("p1", "shop-1", new BigDecimal("100000"), 3, null)));

		CheckoutCheckResponse response = checkoutService.validate("user-1");

		assertThat(response.issues().get(0).type()).isEqualTo(CheckoutService.TYPE_OUT_OF_STOCK);
		assertThat(response.issues().get(0).availableStock()).isEqualTo(3);
	}

	@Test
	void variantUsesVariantPriceAndStock() {
		when(cartService.items("user-1"))
				.thenReturn(List.of(item("p1", "red", 1, "100000"), item("p1", "blue", 1, "110000")));
		when(catalog.findProduct("p1")).thenReturn(Optional.of(new ProductSnapshot(
				"p1", "shop-1", new BigDecimal("100000"), 1,
				List.of(new ProductSnapshot.VariantSnapshot("red", new BigDecimal("100000"), 2),
						new ProductSnapshot.VariantSnapshot("blue", new BigDecimal("90000"), 0)))));

		CheckoutCheckResponse response = checkoutService.validate("user-1");

		// Biến thể "blue" vừa đổi giá (110000 → 90000) vừa hết stock (0 < 1)
		// → hai issue cho cùng một item; "red" vẫn ổn.
		assertThat(response.ok()).isFalse();
		assertThat(response.issues()).hasSize(2);
		assertThat(response.issues()).allSatisfy(issue -> {
			assertThat(issue.productId()).isEqualTo("p1");
			assertThat(issue.variantId()).isEqualTo("blue");
		});
		assertThat(response.issues())
				.extracting(CheckoutIssueDto::type)
				.containsExactly(CheckoutService.TYPE_PRICE_CHANGED, CheckoutService.TYPE_OUT_OF_STOCK);
	}

	@Test
	void deletedProductRaisesNotFoundIssue() {
		when(cartService.items("user-1")).thenReturn(List.of(item("p1", null, 1, "100000")));
		when(catalog.findProduct("p1")).thenReturn(Optional.empty());

		CheckoutCheckResponse response = checkoutService.validate("user-1");

		assertThat(response.issues().get(0).type()).isEqualTo(CheckoutService.TYPE_NOT_FOUND);
	}

	@Test
	void unavailableCatalogStopsWithSingleServiceIssue() {
		when(cartService.items("user-1"))
				.thenReturn(List.of(item("p1", null, 1, "100000"), item("p2", null, 1, "100000")));
		when(catalog.findProduct("p1"))
				.thenThrow(new com.vmarket.cart.exception.ProductCatalogUnavailableException(
						"không kết nối được", null));

		CheckoutCheckResponse response = checkoutService.validate("user-1");

		assertThat(response.ok()).isFalse();
		// Dừng ngay sau item đầu — không sinh một đống issue SERVICE_UNAVAILABLE
		assertThat(response.issues()).hasSize(1);
		assertThat(response.issues().get(0).type())
				.isEqualTo(CheckoutService.TYPE_SERVICE_UNAVAILABLE);
		assertThat(response.issues().get(0).productId()).isEqualTo("p1");
	}
}
