package com.vmarket.cart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
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
 * Hợp đồng với {@code GET /api/products/{id}} của product-service: body thật là
 * {@code ProductDetailResponse{product, similarProducts}} chứ không phải snapshot phẳng.
 */
class RestClientProductCatalogClientTest {

	private static final String BASE_URL = "http://product-service:8084";

	private MockRestServiceServer server;
	private RestClientProductCatalogClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		ProductCatalogProperties properties = new ProductCatalogProperties();
		properties.setBaseUrl(BASE_URL);
		properties.setApiKey("test-internal-key");
		client = new RestClientProductCatalogClient(builder, properties);
	}

	@Test
	void readsNestedProductDetailAndUsesAvailableStockPerVariant() {
		server.expect(requestTo(BASE_URL + "/api/products/P1"))
				.andExpect(header("X-Internal-Api-Key", "test-internal-key"))
				.andRespond(withSuccess("""
						{
						  "product": {
						    "id": "P1",
						    "shopId": "S1",
						    "name": "Ao polo",
						    "availableStock": 12,
						    "variants": [
						      {"id": "V1", "sku": "POLO-M", "price": 249000, "stock": 10,
						       "reservedStock": 3, "availableStock": 7},
						      {"id": "V2", "sku": "POLO-L", "price": 259000, "stock": 5,
						       "reservedStock": 0, "availableStock": 5}
						    ]
						  },
						  "similarProducts": []
						}
						""", MediaType.APPLICATION_JSON));

		Optional<ProductSnapshot> result = client.findProduct("P1");

		assertThat(result).isPresent();
		ProductSnapshot snapshot = result.get();
		assertThat(snapshot.productId()).isEqualTo("P1");
		assertThat(snapshot.shopId()).isEqualTo("S1");
		assertThat(snapshot.price()).as("nhieu bien the thi khong co gia chung").isNull();
		assertThat(snapshot.variants()).hasSize(2);
		assertThat(snapshot.variants().get(0).variantId()).isEqualTo("V1");
		assertThat(snapshot.variants().get(0).price()).isEqualByComparingTo(new BigDecimal("249000"));
		assertThat(snapshot.variants().get(0).stock()).isEqualTo(7);
		server.verify();
	}

	@Test
	void singleVariantProductExposesItsPriceForItemsWithoutVariantId() {
		server.expect(requestTo(BASE_URL + "/api/products/P2"))
				.andRespond(withSuccess("""
						{"product": {"id": "P2", "shopId": "S1", "availableStock": 4,
						  "variants": [{"id": "V9", "price": 120000, "availableStock": 4}]},
						 "similarProducts": []}
						""", MediaType.APPLICATION_JSON));

		ProductSnapshot snapshot = client.findProduct("P2").orElseThrow();

		assertThat(snapshot.price()).isEqualByComparingTo(new BigDecimal("120000"));
		assertThat(snapshot.stock()).isEqualTo(4);
	}

	@Test
	void notFoundMeansProductIsGone() {
		server.expect(requestTo(BASE_URL + "/api/products/GONE"))
				.andRespond(withStatus(HttpStatus.NOT_FOUND));

		assertThat(client.findProduct("GONE")).isEmpty();
	}

	@Test
	void bodyWithoutProductIsTreatedAsUnavailable() {
		server.expect(requestTo(BASE_URL + "/api/products/P3"))
				.andRespond(withSuccess("{\"similarProducts\": []}", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.findProduct("P3"))
				.isInstanceOf(ProductCatalogUnavailableException.class);
	}
}
