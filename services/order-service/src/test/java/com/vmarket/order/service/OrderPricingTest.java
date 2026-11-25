package com.vmarket.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.vmarket.order.client.CartServiceClient.CartItemView;
import com.vmarket.order.client.ProductServiceClient;
import com.vmarket.order.client.ProductServiceClient.ProductPrices;
import com.vmarket.order.client.ProductServiceClient.Variant;
import com.vmarket.order.exception.ApiException;

class OrderPricingTest {

	private static final String SHOP = "01JBQ9SHOP0000000000000001";

	private ProductServiceClient client;
	private OrderPricing pricing;

	@BeforeEach
	void setUp() {
		client = mock(ProductServiceClient.class);
		pricing = new OrderPricing(client);
	}

	private void product(String productId, String shopId, Variant... variants) {
		when(client.findProduct(productId))
				.thenReturn(Optional.of(new ProductPrices(productId, shopId, List.of(variants))));
	}

	private static CartItemView line(String productId, String variantId, int quantity, String unitPrice) {
		BigDecimal price = unitPrice == null ? null : new BigDecimal(unitPrice);
		return new CartItemView(productId, variantId, SHOP, quantity, price,
				price == null ? null : price.multiply(BigDecimal.valueOf(quantity)));
	}

	private static void assertConflict(Runnable call, String code) {
		assertThatThrownBy(call::run)
				.isInstanceOfSatisfying(ApiException.class, e -> {
					assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
					assertThat(e.getCode()).isEqualTo(code);
				});
	}

	@Test
	void zeroPriceIsUnavailable() {
		product("P1", SHOP, new Variant("V1", BigDecimal.ZERO));
		assertConflict(() -> pricing.reprice(List.of(line("P1", "V1", 1, "0"))), "PRODUCT_UNAVAILABLE");
	}

	@Test
	void negativePriceIsUnavailable() {
		product("P1", SHOP, new Variant("V1", new BigDecimal("-1000")));
		assertConflict(() -> pricing.reprice(List.of(line("P1", "V1", 1, "-1000"))), "PRODUCT_UNAVAILABLE");
	}

	@Test
	void nullCatalogPriceIsUnavailable() {
		product("P1", SHOP, new Variant("V1", null));
		assertConflict(() -> pricing.reprice(List.of(line("P1", "V1", 1, "1000"))), "PRODUCT_UNAVAILABLE");
	}

	@Test
	void missingProductIsUnavailable() {
		when(client.findProduct("P1")).thenReturn(Optional.empty());
		assertConflict(() -> pricing.reprice(List.of(line("P1", "V1", 1, "1000"))), "PRODUCT_UNAVAILABLE");
	}

	@Test
	void missingVariantIdWithSeveralVariantsIsUnavailable() {
		product("P1", SHOP, new Variant("V1", new BigDecimal("1000")), new Variant("V2", new BigDecimal("2000")));
		assertConflict(() -> pricing.reprice(List.of(line("P1", null, 1, "1000"))), "PRODUCT_UNAVAILABLE");
	}

	@Test
	void missingVariantIdWithSingleVariantUsesThatPrice() {
		product("P1", SHOP, new Variant("V1", new BigDecimal("1000")));
		Map<String, CartItemView> priced = pricing.reprice(List.of(line("P1", null, 2, "1000")));
		assertThat(priced.get(OrderPricing.lineKey("P1", null)).lineTotal()).isEqualByComparingTo("2000");
	}

	@Test
	void shopMismatchIsUnavailable() {
		product("P1", "01JBQ9SHOP0000000000000002", new Variant("V1", new BigDecimal("1000")));
		assertConflict(() -> pricing.reprice(List.of(line("P1", "V1", 1, "1000"))), "PRODUCT_UNAVAILABLE");
	}

	@Test
	void unavailableWinsOverPriceChange() {
		product("P1", SHOP, new Variant("V1", new BigDecimal("1500")));
		product("P2", SHOP, new Variant("V2", BigDecimal.ZERO));
		assertConflict(() -> pricing.reprice(List.of(line("P1", "V1", 1, "1000"), line("P2", "V2", 1, "1000"))),
				"PRODUCT_UNAVAILABLE");
	}

	@Test
	void changedPriceIsRejected() {
		product("P1", SHOP, new Variant("V1", new BigDecimal("1500")));
		assertConflict(() -> pricing.reprice(List.of(line("P1", "V1", 1, "1000"))), "PRICE_CHANGED");
	}

	@Test
	void missingCartPriceCountsAsChanged() {
		product("P1", SHOP, new Variant("V1", new BigDecimal("1000")));
		assertConflict(() -> pricing.reprice(List.of(line("P1", "V1", 1, null))), "PRICE_CHANGED");
	}

	@Test
	void sameAmountWithDifferentScaleIsNotAPriceChange() {
		product("P1", SHOP, new Variant("V1", new BigDecimal("250000.00")));
		Map<String, CartItemView> priced = pricing.reprice(List.of(line("P1", "V1", 1, "250000")));
		assertThat(priced.get(OrderPricing.lineKey("P1", "V1")).unitPrice()).isEqualByComparingTo("250000");
	}

	@Test
	void linesArePricedFromCatalogAndKeepOrder() {
		product("P1", SHOP, new Variant("V1", new BigDecimal("1000")), new Variant("V2", new BigDecimal("2500")));
		product("P2", SHOP, new Variant("V3", new BigDecimal("700")));
		List<CartItemView> cart = List.of(line("P2", "V3", 3, "700"), line("P1", "V2", 2, "2500"),
				line("P1", "V1", 1, "1000"));

		List<CartItemView> result = OrderPricing.apply(cart, pricing.reprice(cart));

		assertThat(result).extracting(CartItemView::variantId).containsExactly("V3", "V2", "V1");
		assertThat(result).extracting(CartItemView::lineTotal)
				.usingElementComparator(BigDecimal::compareTo)
				.containsExactly(new BigDecimal("2100"), new BigDecimal("5000"), new BigDecimal("1000"));
		BigDecimal total = result.stream().map(CartItemView::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
		assertThat(total).isEqualByComparingTo("8100");
		// Một sản phẩm nhiều biến thể chỉ gọi product-service một lần.
		verify(client, times(1)).findProduct("P1");
	}

	@Test
	void productServiceOutagePropagatesBeforeAnyPricing() {
		when(client.findProduct("P1")).thenThrow(ApiException.unavailable("PRODUCT_SERVICE_UNAVAILABLE", "x"));
		assertThatThrownBy(() -> pricing.reprice(List.of(line("P1", "V1", 1, "1000"))))
				.isInstanceOfSatisfying(ApiException.class,
						e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
	}
}
