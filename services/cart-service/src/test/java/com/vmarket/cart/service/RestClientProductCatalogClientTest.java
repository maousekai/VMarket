package com.vmarket.cart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.vmarket.cart.config.ProductCatalogProperties;
import com.vmarket.cart.dto.ProductSnapshot;
import com.vmarket.cart.exception.ProductCatalogUnavailableException;

/**
 * Client phải đọc được CẢ dạng bọc thật của product-service lẫn dạng phẳng cũ.
 */
class RestClientProductCatalogClientTest {

	private static final String VARIANT_ID = "3f2b8c1e-5a47-4d0b-9c1e-0a1b2c3d4e5f";

	private MockRestServiceServer server;
	private RestClientProductCatalogClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		client = new RestClientProductCatalogClient(builder, new ProductCatalogProperties());
	}

	private void respond(String json) {
		server.expect(requestTo("http://localhost:8084/api/products/p1"))
				.andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
	}

	@Test
	void wrappedShapeWithVariantsMapsUuidVariantAndAvailableStock() {
		respond("""
				{"product":{"id":"p1","shopId":"shop-9","status":"ACTIVE","currency":"VND",
				 "variants":[{"id":"%s","sku":"SKU-1","price":35000,"stock":22,
				   "reservedStock":5,"availableStock":17,"soldCount":0}],
				 "minPrice":35000,"maxPrice":35000,"availableStock":17,"moderationRemoved":false},
				 "similarProducts":[{"id":"x"}]}
				""".formatted(VARIANT_ID));

		ProductSnapshot s = client.findProduct("p1").orElseThrow();

		assertThat(s.productId()).isEqualTo("p1");
		assertThat(s.shopId()).isEqualTo("shop-9");
		assertThat(s.variants()).hasSize(1);
		assertThat(s.variants().get(0).variantId()).isEqualTo(VARIANT_ID);
		assertThat(s.variants().get(0).price()).isEqualByComparingTo("35000");
		assertThat(s.variants().get(0).stock()).isEqualTo(17);
	}

	@Test
	void wrappedVariantFallsBackToStockWhenAvailableStockMissing() {
		respond("""
				{"product":{"id":"p1","shopId":"s","status":"ACTIVE",
				 "variants":[{"id":"%s","price":100,"stock":8}]}}
				""".formatted(VARIANT_ID));

		ProductSnapshot s = client.findProduct("p1").orElseThrow();

		assertThat(s.variants().get(0).stock()).isEqualTo(8);
	}

	@Test
	void wrappedShapeWithoutVariantsUsesMinPriceAndAvailableStock() {
		respond("""
				{"product":{"id":"p1","shopId":"shop-9","status":"ACTIVE","variants":[],
				 "minPrice":50000,"maxPrice":50000,"availableStock":12},"similarProducts":[]}
				""");

		ProductSnapshot s = client.findProduct("p1").orElseThrow();

		assertThat(s.variants()).isNull();
		assertThat(s.price()).isEqualByComparingTo(new BigDecimal("50000"));
		assertThat(s.stock()).isEqualTo(12);
	}

	@Test
	void legacyFlatShapeStillSupported() {
		respond("""
				{"productId":"p1","shopId":"shop-1","price":100,"stock":3,
				 "variants":[{"variantId":"v1","price":120,"stock":4}]}
				""");

		ProductSnapshot s = client.findProduct("p1").orElseThrow();

		assertThat(s.productId()).isEqualTo("p1");
		assertThat(s.shopId()).isEqualTo("shop-1");
		assertThat(s.price()).isEqualByComparingTo("100");
		assertThat(s.stock()).isEqualTo(3);
		assertThat(s.variants().get(0).variantId()).isEqualTo("v1");
		assertThat(s.variants().get(0).stock()).isEqualTo(4);
	}

	@Test
	void nonActiveStatusIsTreatedAsUnavailable() {
		respond("""
				{"product":{"id":"p1","shopId":"s","status":"HIDDEN","variants":[]}}
				""");

		assertThat(client.findProduct("p1")).isEqualTo(Optional.empty());
	}

	@Test
	void moderationRemovedIsTreatedAsUnavailable() {
		respond("""
				{"product":{"id":"p1","shopId":"s","status":"ACTIVE","moderationRemoved":true}}
				""");

		assertThat(client.findProduct("p1")).isEmpty();
	}

	@Test
	void notFoundMapsToEmpty() {
		server.expect(requestTo("http://localhost:8084/api/products/p1"))
				.andRespond(withStatus(HttpStatus.NOT_FOUND));

		assertThat(client.findProduct("p1")).isEmpty();
	}

	@Test
	void serverErrorMapsToUnavailable() {
		server.expect(requestTo("http://localhost:8084/api/products/p1"))
				.andRespond(withServerError());

		assertThatThrownBy(() -> client.findProduct("p1"))
				.isInstanceOf(ProductCatalogUnavailableException.class);
	}

	@Test
	void emptyBodyMapsToUnavailable() {
		server.expect(requestTo("http://localhost:8084/api/products/p1"))
				.andRespond(withSuccess());

		assertThatThrownBy(() -> client.findProduct("p1"))
				.isInstanceOf(ProductCatalogUnavailableException.class);
	}
}
