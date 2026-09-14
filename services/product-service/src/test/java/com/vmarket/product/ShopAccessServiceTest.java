package com.vmarket.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.vmarket.product.exception.ApiException;
import com.vmarket.product.model.ShopCatalogAccess;
import com.vmarket.product.repository.ShopCatalogAccessRepository;
import com.vmarket.product.service.CatalogProjectionService;
import com.vmarket.product.service.ShopAccessService;

@ExtendWith(MockitoExtension.class)
class ShopAccessServiceTest {
	@Mock ShopCatalogAccessRepository shopRepository;
	@Mock CatalogProjectionService projections;
	private ShopAccessService service;

	@BeforeEach
	void setUp() {
		service = new ShopAccessService(shopRepository, projections);
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
	void suspensionUpdatesProjectionAndProcessesProductsInBatches() {
		ShopCatalogAccess shop = new ShopCatalogAccess("shop-1", "seller-1", true, Instant.now());
		when(shopRepository.findById("shop-1")).thenReturn(Optional.of(shop));

		service.suspend("shop-1");

		verify(shopRepository).save(org.mockito.ArgumentMatchers.argThat(saved -> !saved.isActive()));
		verify(projections).synchronizeShopProducts("shop-1", false);
	}

	@Test
	void approvalUpdatesProjectionAndProcessesProductsInBatches() {
		service.approve("shop-1", "seller-1");

		verify(shopRepository).save(org.mockito.ArgumentMatchers.argThat(ShopCatalogAccess::isActive));
		verify(projections).synchronizeShopProducts("shop-1", true);
	}

	@Test
	void staleShopSnapshotCannotUndoNewerProjection() {
		ShopCatalogAccess current = new ShopCatalogAccess("shop-1", "seller-1", false,
				Instant.parse("2026-09-14T10:00:00Z"));
		when(shopRepository.findById("shop-1")).thenReturn(Optional.of(current));

		service.reconcile("shop-1", "seller-1", true, Instant.parse("2026-09-14T09:00:00Z"));

		verify(shopRepository, never()).save(org.mockito.ArgumentMatchers.any());
		verify(projections, never()).synchronizeShopProducts("shop-1", true);
	}
}
