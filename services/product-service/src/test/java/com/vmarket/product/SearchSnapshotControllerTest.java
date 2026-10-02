package com.vmarket.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.vmarket.product.controller.SearchSnapshotController;
import com.vmarket.product.exception.GlobalExceptionHandler;
import com.vmarket.product.model.Product;
import com.vmarket.product.model.ProductStatus;
import com.vmarket.product.model.ProductVariant;
import com.vmarket.product.repository.ProductRepository;
import com.vmarket.product.security.InternalApiKeyGuard;
import com.vmarket.product.service.ProductMapper;

class SearchSnapshotControllerTest {
	private final ProductRepository products = mock(ProductRepository.class);
	private final MongoTemplate mongo = mock(MongoTemplate.class);
	private MockMvc mvc;
	private Product product;
	@BeforeEach void setup() {
		mvc = MockMvcBuilders.standaloneSetup(new SearchSnapshotController(products, mongo,
				new InternalApiKeyGuard("test-key"), new ProductMapper()))
				.setControllerAdvice(new GlobalExceptionHandler()).build();
		product = new Product();
		product.setId("product-1"); product.setShopId("shop-1"); product.setName("Áo thun");
		product.setDescription("Cotton"); product.setStatus(ProductStatus.ACTIVE); product.setVersion(0L);
		product.setCategoryId("child"); product.setCategoryPath(List.of("parent", "child"));
		product.setCreatedAt(Instant.parse("2026-10-02T00:00:00Z")); product.setUpdatedAt(product.getCreatedAt());
		product.setVariants(List.of(new ProductVariant("v1", "sku1", Map.of(), 100, 2, 0, 0),
				new ProductVariant("v2", "sku2", Map.of(), 1000, 2, 0, 0)));
		when(products.findById("product-1")).thenReturn(Optional.of(product));
	}
	@Test void credentialsRequiredAndNoPublicSellerData() throws Exception {
		mvc.perform(get("/api/products/internal/search-snapshots/product-1")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/products/internal/search-snapshots/product-1").header("X-Internal-Api-Key", "test-key"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.productVersion").value(0))
				.andExpect(jsonPath("$.variantPrices[1]").value(1000)).andExpect(jsonPath("$.minPrice").value(100))
				.andExpect(jsonPath("$.sellerId").doesNotExist()).andExpect(jsonPath("$.catalogVisible").value(true));
	}
	@Test void deletionRetainsRevisionAndMissingRevisionRejected() throws Exception {
		product.setDeletedAt(Instant.now()); product.setVersion(3L);
		mvc.perform(get("/api/products/internal/search-snapshots/product-1").header("X-Internal-Api-Key", "test-key"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(true))
				.andExpect(jsonPath("$.catalogVisible").value(false)).andExpect(jsonPath("$.productVersion").value(3))
				.andExpect(jsonPath("$.name").doesNotExist());
		product.setVersion(null);
		mvc.perform(get("/api/products/internal/search-snapshots/product-1").header("X-Internal-Api-Key", "test-key"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SNAPSHOT_VERSION_MISSING"));
	}
	@Test void exportUsesNativeIdAndBoundedLookAhead() throws Exception {
		when(mongo.find(any(Query.class), eq(Product.class))).thenReturn(List.of(product, product));
		mvc.perform(get("/api/products/internal/search-snapshots?page=2&size=1").header("X-Internal-Api-Key", "test-key"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.hasMore").value(true));
		ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
		verify(mongo).find(query.capture(), eq(Product.class));
		assertThat(query.getValue().getSortObject().get("_id")).isEqualTo(1);
		assertThat(query.getValue().getSkip()).isEqualTo(2);
		assertThat(query.getValue().getLimit()).isEqualTo(2);
		mvc.perform(get("/api/products/internal/search-snapshots?size=101").header("X-Internal-Api-Key", "test-key"))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/products/internal/search-snapshots?size=1&size=2").header("X-Internal-Api-Key", "test-key"))
				.andExpect(status().isBadRequest());
	}
}
