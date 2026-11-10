package com.vmarket.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.vmarket.shop.client.ProductServiceClient;
import com.vmarket.shop.client.ProductServiceClient.ShopAccessSnapshot;
import com.vmarket.shop.entity.Shop;
import com.vmarket.shop.entity.ShopStatus;
import com.vmarket.shop.repository.ShopRepository;
import com.vmarket.shop.service.ShopCatalogAccessReconciler;

class ShopCatalogAccessReconcilerTest {

	private final ShopRepository repository = mock(ShopRepository.class);
	private final ProductServiceClient client = mock(ProductServiceClient.class);
	private final ShopCatalogAccessReconciler reconciler = new ShopCatalogAccessReconciler(repository, client);

	private static Shop shop(String id, String owner, ShopStatus status) {
		Shop shop = new Shop();
		shop.setId(id);
		shop.setOwnerId(owner);
		shop.setStatus(status);
		shop.setUpdatedAt(Instant.parse("2026-11-03T08:43:38Z"));
		return shop;
	}

	@Test
	@SuppressWarnings("unchecked")
	void pushesApprovedShopsWithTheirActiveFlag() {
		when(repository.findByStatusIn(anyList(), any(Pageable.class))).thenReturn(new PageImpl<>(
				List.of(shop("S1", "U1", ShopStatus.ACTIVE), shop("S2", "U2", ShopStatus.SUSPENDED))));
		when(client.reconcileShopAccess(anyList())).thenReturn(true);

		assertThat(reconciler.reconcile()).isEqualTo(2);

		ArgumentCaptor<List<ShopAccessSnapshot>> sent = ArgumentCaptor.forClass(List.class);
		verify(client).reconcileShopAccess(sent.capture());
		assertThat(sent.getValue()).containsExactly(
				new ShopAccessSnapshot("S1", "U1", true, Instant.parse("2026-11-03T08:43:38Z")),
				new ShopAccessSnapshot("S2", "U2", false, Instant.parse("2026-11-03T08:43:38Z")));
	}

	@Test
	void doesNothingWithoutApprovedShops() {
		when(repository.findByStatusIn(anyList(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

		assertThat(reconciler.reconcile()).isZero();
		verify(client, never()).reconcileShopAccess(anyList());
	}

	@Test
	void stopsQuietlyWhenProductServiceIsDown() {
		when(repository.findByStatusIn(anyList(), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(shop("S1", "U1", ShopStatus.ACTIVE))));
		when(client.reconcileShopAccess(anyList())).thenReturn(false);

		assertThat(reconciler.reconcile()).isZero();
	}
}
