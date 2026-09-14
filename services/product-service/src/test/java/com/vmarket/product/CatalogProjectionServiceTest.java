package com.vmarket.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.vmarket.product.model.Category;
import com.vmarket.product.model.Product;
import com.vmarket.product.model.ProductStatus;
import com.vmarket.product.model.ProductVariant;
import com.vmarket.product.repository.CategoryRepository;
import com.vmarket.product.repository.ProductRepository;
import com.vmarket.product.service.CatalogProjectionBatchWriter;
import com.vmarket.product.service.CatalogProjectionService;
import com.vmarket.product.service.ProductDerivedFields;

@ExtendWith(MockitoExtension.class)
class CatalogProjectionServiceTest {
	@Mock CategoryRepository categoryRepository;
	@Mock ProductRepository productRepository;
	@Mock CatalogProjectionBatchWriter writer;
	private CatalogProjectionService service;

	@BeforeEach
	void setUp() {
		service = new CatalogProjectionService(categoryRepository, productRepository,
				new ProductDerivedFields(), writer);
	}

	@Test
	void categoryProjectionIncludesAncestorsAndEffectiveVisibility() {
		Category root = category("root", null, false);
		Category child = category("child", "root", true);
		when(categoryRepository.findById("child")).thenReturn(Optional.of(child));
		when(categoryRepository.findById("root")).thenReturn(Optional.of(root));

		var projection = service.categoryProjection("child");

		assertThat(projection.path()).containsExactly("child", "root");
		assertThat(projection.visible()).isFalse();
	}

	@Test
	void categoryChangeProjectsDescendantsInBoundedBatches() {
		Category root = category("root", null, false);
		Category child = category("child", "root", true);
		Product product = product("p1", ProductStatus.ACTIVE);
		product.setCategoryId("child");
		when(categoryRepository.findByParentId("root")).thenReturn(List.of(child));
		when(categoryRepository.findByParentId("child")).thenReturn(List.of());
		when(categoryRepository.findById("child")).thenReturn(Optional.of(child));
		when(categoryRepository.findById("root")).thenReturn(Optional.of(root));
		when(productRepository.findTop100ByCategoryIdAndDeletedAtIsNullAndIdGreaterThanOrderByIdAsc(
				"root", "")).thenReturn(List.of());
		when(productRepository.findTop100ByCategoryIdAndDeletedAtIsNullAndIdGreaterThanOrderByIdAsc(
				"child", "")).thenReturn(List.of(product));
		when(productRepository.findTop100ByCategoryIdAndDeletedAtIsNullAndIdGreaterThanOrderByIdAsc(
				"child", "p1")).thenReturn(List.of());

		service.synchronizeCategorySubtree("root");

		assertThat(product.isCategoryVisible()).isFalse();
		assertThat(product.getCategoryPath()).containsExactly("child", "root");
		verify(writer).saveAndPublish(List.of(product));
	}

	@Test
	void shopSuspensionAndModerationRestoreKeepOriginalStatus() {
		Product product = product("p1", ProductStatus.ACTIVE);
		when(productRepository.findTop100ByShopIdAndDeletedAtIsNullAndIdGreaterThanOrderByIdAsc(
				"shop-1", "")).thenReturn(List.of(product));
		when(productRepository.findTop100ByShopIdAndDeletedAtIsNullAndIdGreaterThanOrderByIdAsc(
				"shop-1", "p1")).thenReturn(List.of());

		service.synchronizeShopProducts("shop-1", false);
		product.setModerationRemoved(true);
		product.setStatusBeforeModeration(ProductStatus.ACTIVE);
		product.setModerationRemoved(false);
		product.setStatusBeforeModeration(null);
		service.synchronizeShopProducts("shop-1", true);

		assertThat(product.isShopSuspended()).isFalse();
		assertThat(product.getStatus()).isEqualTo(ProductStatus.ACTIVE);
		assertThat(product.getStatusBeforeShopSuspension()).isNull();
	}

	@Test
	void approvalWhileModeratedPreservesDesiredStatusForLaterAdminRestore() {
		Product product = product("p1", ProductStatus.HIDDEN);
		product.setShopSuspended(true);
		product.setStatusBeforeShopSuspension(ProductStatus.ACTIVE);
		product.setModerationRemoved(true);
		product.setStatusBeforeModeration(ProductStatus.HIDDEN);
		when(productRepository.findTop100ByShopIdAndDeletedAtIsNullAndIdGreaterThanOrderByIdAsc(
				"shop-1", "")).thenReturn(List.of(product));
		when(productRepository.findTop100ByShopIdAndDeletedAtIsNullAndIdGreaterThanOrderByIdAsc(
				"shop-1", "p1")).thenReturn(List.of());

		service.synchronizeShopProducts("shop-1", true);

		assertThat(product.getStatus()).isEqualTo(ProductStatus.HIDDEN);
		assertThat(product.getStatusBeforeModeration()).isEqualTo(ProductStatus.ACTIVE);
	}

	@Test
	void migrationBackfillsDerivedFields() {
		Product product = product("p1", ProductStatus.ACTIVE);
		product.setCategoryId("root");
		product.setMinPrice(null);
		product.setMaxPrice(null);
		product.setAvailableStock(0);
		when(categoryRepository.findById("root")).thenReturn(Optional.of(category("root", null, true)));
		when(productRepository.findTop100ByDeletedAtIsNullAndIdGreaterThanOrderByIdAsc(""))
				.thenReturn(List.of(product));
		when(productRepository.findTop100ByDeletedAtIsNullAndIdGreaterThanOrderByIdAsc("p1"))
				.thenReturn(List.of());

		service.synchronizeAllProducts();

		assertThat(product.getMinPrice()).isEqualByComparingTo("10");
		assertThat(product.getAvailableStock()).isEqualTo(8);
		verify(writer).saveAndPublish(List.of(product));
	}

	private Category category(String id, String parentId, boolean active) {
		return new Category(id, id, id, parentId, active, 0, Instant.now(), Instant.now());
	}

	private Product product(String id, ProductStatus status) {
		Product product = new Product();
		product.setId(id);
		product.setShopId("shop-1");
		product.setStatus(status);
		product.setCategoryVisible(true);
		product.setVariants(new ArrayList<>(List.of(new ProductVariant("v1", "SKU", java.util.Map.of(),
				BigDecimal.TEN, 10, 2, 0))));
		return product;
	}
}
