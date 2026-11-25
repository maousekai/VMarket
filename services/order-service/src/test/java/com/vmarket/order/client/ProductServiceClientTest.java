package com.vmarket.order.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.vmarket.order.exception.ApiException;

class ProductServiceClientTest {

	private MockRestServiceServer server;
	private ProductServiceClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl("http://product");
		server = MockRestServiceServer.bindTo(builder).build();
		client = new ProductServiceClient(builder.build());
	}

	@Test
	void readsVariantPrices() {
		server.expect(requestTo("http://product/api/products/p1")).andRespond(withSuccess("""
				{"product":{"id":"p1","shopId":"s1","variants":[{"id":"v1","price":1000},{"id":"v2","price":2000}]},
				 "similarProducts":[]}
				""", MediaType.APPLICATION_JSON));

		ProductServiceClient.ProductPrices prices = client.findProduct("p1").orElseThrow();

		assertThat(prices.priceOf("v2")).isEqualByComparingTo(new BigDecimal("2000"));
		assertThat(prices.priceOf("missing")).isNull();
		// Nhiều biến thể mà dòng hàng không có variantId -> không đoán giá
		assertThat(prices.priceOf(null)).isNull();
	}

	@Test
	void singleVariantPriceIsUsedWhenLineHasNoVariant() {
		server.expect(requestTo("http://product/api/products/p1")).andRespond(withSuccess(
				"{\"product\":{\"id\":\"p1\",\"variants\":[{\"id\":\"v1\",\"price\":1500}]}}",
				MediaType.APPLICATION_JSON));

		assertThat(client.findProduct("p1").orElseThrow().priceOf(null)).isEqualByComparingTo("1500");
	}

	@Test
	void notFoundMeansRemoved() {
		server.expect(requestTo("http://product/api/products/gone")).andRespond(withStatus(HttpStatus.NOT_FOUND));
		assertThat(client.findProduct("gone")).isEmpty();
	}

	@Test
	void serverErrorOrEmptyBodyIs503() {
		server.expect(requestTo("http://product/api/products/p1")).andRespond(withServerError());
		assertThatThrownBy(() -> client.findProduct("p1"))
				.isInstanceOfSatisfying(ApiException.class,
						ex -> assertThat(ex.getMessage()).isNotBlank());

		server.reset();
		server.expect(requestTo("http://product/api/products/p2")).andRespond(withSuccess("{}",
				MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> client.findProduct("p2")).isInstanceOf(ApiException.class);
	}
}
