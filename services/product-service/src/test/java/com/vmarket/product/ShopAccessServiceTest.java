package com.vmarket.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.vmarket.product.event.ProductEventFactory;
import com.vmarket.product.event.ProductEventPublisher;
import com.vmarket.product.exception.ApiException;
import com.vmarket.product.model.Product;
import com.vmarket.product.model.ProductStatus;
import com.vmarket.product.model.ShopCatalogAccess;
import com.vmarket.product.repository.ProductRepository;
import com.vmarket.product.repository.ShopCatalogAccessRepository;
import com.vmarket.product.service.ShopAccessService;

@ExtendWith(MockitoExtension.class)
class ShopAccessServiceTest {
	@Mock ShopCatalogAccessRepository shopRepository;
	@Mock ProductRepository productRepository;
	@Mock ProductEventPublisher publisher;
	private ShopAccessService service;

	@BeforeEach
	void setUp() {
		service = new ShopAccessService(shopRepository, productRepository, publisher, new ProductEventFactory());
	}

	@Test
	void activeShopCanOnlyBeUsedByItsOwner() {
		when(shopRepository.findById("shop-1"))
				.thenReturn(Optional.of(new ShopCatalogAccess("shop-1", "seller-1", true, Instant.now())));

		service.requireActiveOwner("shop-1", "seller-1");
		assertThatThrownBy(() -> service.requireActiveOwner("shop-1", "seller-2"))
				.isInstanceOf(ApiException.class).hasMessageContaining("không sở hữu");
	}

	@Test
	void suspensionHidesProductsAndPublishesUpdatedSnapshot() {
		ShopCatalogAccess shop = new ShopCatalogAccess("shop-1", "seller-1", true, Instant.now());
		Product product = product(ProductStatus.ACTIVE);
		when(shopRepository.findById("shop-1")).thenReturn(Optional.of(shop));
		when(productRepository.findAllByShopIdAndDeletedAtIsNull("shop-1")).thenReturn(List.of(product));
		when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

		service.suspend("shop-1");

		assertThat(shop.isActive()).isFalse();
		assertThat(product.isShopSuspended()).isTrue();
		assertThat(product.getStatus()).isEqualTo(ProductStatus.HIDDEN);
		assertThat(product.getStatusBeforeShopSuspension()).isEqualTo(ProductStatus.ACTIVE);
		verify(publisher).publishUpdated(any());
	}

	@Test
	void approvalRestoresStatusSavedBeforeSuspension() {
		Product product = product(ProductStatus.HIDDEN);
		product.setShopSuspended(true);
		product.setStatusBeforeShopSuspension(ProductStatus.ACTIVE);
		when(productRepository.findAllByShopIdAndDeletedAtIsNull("shop-1")).thenReturn(List.of(product));
		when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

		service.approve("shop-1", "seller-1");

		assertThat(product.isShopSuspended()).isFalse();
		assertThat(product.getStatus()).isEqualTo(ProductStatus.ACTIVE);
	}

	private Product product(ProductStatus status) {
		Product product = new Product();
		product.setId("product-1");
		product.setShopId("shop-1");
		product.setSellerId("seller-1");
		product.setName("Tên");
		product.setStatus(status);
		return product;
	}
}
