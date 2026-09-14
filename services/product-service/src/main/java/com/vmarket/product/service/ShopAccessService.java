package com.vmarket.product.service;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.product.exception.ApiException;
import com.vmarket.product.event.ProductEventFactory;
import com.vmarket.product.event.ProductEventPublisher;
import com.vmarket.product.model.Product;
import com.vmarket.product.model.ProductStatus;
import com.vmarket.product.model.ShopCatalogAccess;
import com.vmarket.product.repository.ProductRepository;
import com.vmarket.product.repository.ShopCatalogAccessRepository;

@Service
public class ShopAccessService {
	private final ShopCatalogAccessRepository shopRepository;
	private final ProductRepository productRepository;
	private final ProductEventPublisher publisher;
	private final ProductEventFactory eventFactory;

	public ShopAccessService(ShopCatalogAccessRepository shopRepository, ProductRepository productRepository,
			ProductEventPublisher publisher, ProductEventFactory eventFactory) {
		this.shopRepository = shopRepository;
		this.productRepository = productRepository;
		this.publisher = publisher;
		this.eventFactory = eventFactory;
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
		shopRepository.save(new ShopCatalogAccess(shopId, sellerId, true, Instant.now()));
		for (Product product : productRepository.findAllByShopIdAndDeletedAtIsNull(shopId)) {
			if (!product.isShopSuspended()) continue;
			product.setShopSuspended(false);
			if (!product.isModerationRemoved()) {
				product.setStatus(product.getStatusBeforeShopSuspension() == null
						? ProductStatus.DRAFT : product.getStatusBeforeShopSuspension());
			}
			product.setStatusBeforeShopSuspension(null);
			product.setUpdatedAt(Instant.now());
			Product saved = productRepository.save(product);
			publisher.publishUpdated(eventFactory.updated(saved));
		}
	}

	@Transactional
	public void suspend(String shopId) {
		ShopCatalogAccess shop = shopRepository.findById(shopId).orElse(null);
		if (shop != null) {
			shop.setActive(false);
			shop.setUpdatedAt(Instant.now());
			shopRepository.save(shop);
		}
		for (Product product : productRepository.findAllByShopIdAndDeletedAtIsNull(shopId)) {
			if (!product.isShopSuspended()) product.setStatusBeforeShopSuspension(product.getStatus());
			product.setShopSuspended(true);
			product.setStatus(ProductStatus.HIDDEN);
			product.setUpdatedAt(Instant.now());
			Product saved = productRepository.save(product);
			publisher.publishUpdated(eventFactory.updated(saved));
		}
	}
}
