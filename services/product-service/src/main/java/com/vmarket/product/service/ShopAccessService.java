package com.vmarket.product.service;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.product.exception.ApiException;
import com.vmarket.product.model.ShopCatalogAccess;
import com.vmarket.product.repository.ShopCatalogAccessRepository;

@Service
public class ShopAccessService {
	private final ShopCatalogAccessRepository shopRepository;
	private final CatalogProjectionService projections;

	public ShopAccessService(ShopCatalogAccessRepository shopRepository, CatalogProjectionService projections) {
		this.shopRepository = shopRepository;
		this.projections = projections;
	}

	public void requireActiveOwner(String shopId, String sellerId) {
		ShopCatalogAccess shop = shopRepository.findById(shopId)
				.orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "SHOP_NOT_READY",
						"Gian hàng chưa được duyệt hoặc chưa đồng bộ sang Product Service"));
		if (!sellerId.equals(shop.getSellerId())) {
			throw new ApiException(HttpStatus.FORBIDDEN, "SHOP_NOT_OWNED", "Bạn không sở hữu gian hàng này");
		}
		if (!shop.isActive()) {
			throw new ApiException(HttpStatus.CONFLICT, "SHOP_SUSPENDED", "Gian hàng đang bị đình chỉ");
		}
	}

	@Transactional
	public void approve(String shopId, String sellerId) {
		approve(shopId, sellerId, Instant.now(), false);
	}

	@Transactional
	public void suspend(String shopId) {
		suspend(shopId, Instant.now(), false);
	}

	@Transactional
	public void approve(String shopId, String sellerId, Instant sourceUpdatedAt, boolean reconciliation) {
		applySnapshot(shopId, sellerId, true, sourceUpdatedAt, reconciliation);
	}

	@Transactional
	public void suspend(String shopId, Instant sourceUpdatedAt, boolean reconciliation) {
		ShopCatalogAccess existing = shopRepository.findById(shopId).orElse(null);
		String sellerId = existing == null ? null : existing.getSellerId();
		applySnapshot(shopId, sellerId, false, sourceUpdatedAt, reconciliation);
	}

	@Transactional
	public void reconcile(String shopId, String sellerId, boolean active, Instant sourceUpdatedAt) {
		applySnapshot(shopId, sellerId, active, sourceUpdatedAt, true);
	}

	private void applySnapshot(String shopId, String sellerId, boolean active,
			Instant sourceUpdatedAt, boolean reconciliation) {
		// ponytail: one transaction keeps shop and products atomic; gate reads by shop access if large catalogs exceed Mongo's transaction limit.
		Instant sourceTime = sourceUpdatedAt == null ? Instant.now() : sourceUpdatedAt;
		ShopCatalogAccess current = shopRepository.findById(shopId).orElse(null);
		if (current != null && current.getUpdatedAt() != null && sourceTime.isBefore(current.getUpdatedAt())) return;
		String owner = sellerId == null && current != null ? current.getSellerId() : sellerId;
		ShopCatalogAccess saved = new ShopCatalogAccess(shopId, owner, active, sourceTime,
				reconciliation ? Instant.now() : current == null ? null : current.getReconciledAt());
		shopRepository.save(saved);
		projections.synchronizeShopProducts(shopId, active);
	}
}
