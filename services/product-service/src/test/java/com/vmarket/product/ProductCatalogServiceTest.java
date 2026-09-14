package com.vmarket.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.vmarket.product.dto.ProductRequest;
import com.vmarket.product.exception.ApiException;
import com.vmarket.product.event.ProductEventPublisher;
import com.vmarket.product.model.Category;
import com.vmarket.product.model.Product;
import com.vmarket.product.model.ProductStatus;
import com.vmarket.product.model.ProductVariant;
import com.vmarket.product.repository.ProductCatalogQuery;
import com.vmarket.product.repository.ProductRepository;
import com.vmarket.product.service.BrandService;
import com.vmarket.product.service.CategoryService;
import com.vmarket.product.service.ProductCatalogService;
import com.vmarket.product.service.ProductMapper;

@ExtendWith(MockitoExtension.class)
class ProductCatalogServiceTest {
	@Mock ProductRepository repository;
	@Mock ProductCatalogQuery query;
	@Mock CategoryService categoryService;
	@Mock BrandService brandService;
	@Mock ProductEventPublisher publisher;
	private ProductCatalogService service;

	@BeforeEach
	void setUp() {
		service = new ProductCatalogService(repository, query, categoryService, brandService,
				new ProductMapper(), publisher);
	}

	@Test
	void createPersistsSellerAndPublishesCreatedEvent() {
		when(categoryService.getRequired("cat-1")).thenReturn(activeCategory());
		when(repository.save(any(Product.class))).thenAnswer(invocation -> {
			Product product = invocation.getArgument(0);
			product.setId("product-1");
			return product;
		});

		var result = service.create("seller-1", request(null, 12));

		assertThat(result.id()).isEqualTo("product-1");
		assertThat(result.availableStock()).isEqualTo(12);
		assertThat(result.variants().get(0).id()).isNotBlank();
		verify(publisher).publishCreated(any());
	}

	@Test
	void updateRejectsAnotherSeller() {
		Product product = product("seller-owner", 0);
		when(repository.findById("product-1")).thenReturn(Optional.of(product));

		assertThatThrownBy(() -> service.update("product-1", "seller-other", request("variant-1", 10)))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("không sở hữu");
		verify(repository, never()).save(any());
	}

	@Test
	void updateCannotReduceStockBelowReservedQuantity() {
		Product product = product("seller-1", 5);
		when(repository.findById("product-1")).thenReturn(Optional.of(product));
		when(categoryService.getRequired("cat-1")).thenReturn(activeCategory());

		assertThatThrownBy(() -> service.update("product-1", "seller-1", request("variant-1", 4)))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("tạm giữ");
	}

	@Test
	void softDeleteHidesProductAndPublishesDeletedEvent() {
		Product product = product("seller-1", 0);
		when(repository.findById("product-1")).thenReturn(Optional.of(product));
		when(repository.save(product)).thenReturn(product);

		service.delete("product-1", "seller-1");

		assertThat(product.getStatus()).isEqualTo(ProductStatus.HIDDEN);
		assertThat(product.getDeletedAt()).isNotNull();
		verify(publisher).publishDeleted(any());
	}

	private ProductRequest request(String variantId, long stock) {
		return new ProductRequest("shop-1", "Áo thun", "Mô tả", List.of("https://img/1.jpg"),
				"cat-1", null, ProductStatus.ACTIVE,
				List.of(new ProductRequest.VariantRequest(variantId, "SKU-1", new HashMap<>(java.util.Map.of("size", "M")),
						new BigDecimal("100000"), stock)));
	}

	private Category activeCategory() {
		return new Category("cat-1", "Thời trang", "thoi-trang", null, true, 0, Instant.now(), Instant.now());
	}

	private Product product(String sellerId, long reserved) {
		Product product = new Product();
		product.setId("product-1");
		product.setSellerId(sellerId);
		product.setShopId("shop-1");
		product.setName("Áo thun");
		product.setDescription("Mô tả");
		product.setImageUrls(List.of("https://img/1.jpg"));
		product.setCategoryId("cat-1");
		product.setStatus(ProductStatus.ACTIVE);
		product.setVariants(List.of(new ProductVariant("variant-1", "SKU-1", new HashMap<>(),
				new BigDecimal("100000"), 10, reserved, 0)));
		product.setCreatedAt(Instant.now());
		product.setUpdatedAt(Instant.now());
		return product;
	}
}
