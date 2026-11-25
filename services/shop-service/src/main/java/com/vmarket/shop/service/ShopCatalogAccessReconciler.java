package com.vmarket.shop.service;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.vmarket.shop.client.ProductServiceClient;
import com.vmarket.shop.client.ProductServiceClient.ShopAccessSnapshot;
import com.vmarket.shop.entity.Shop;
import com.vmarket.shop.entity.ShopStatus;
import com.vmarket.shop.repository.ShopRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Đối soát quyền đăng bán giữa shop-service và product-service.
 *
 * <p>Product-service chỉ cho seller tạo sản phẩm khi đã nhận sự kiện
 * {@code ShopApproved}. Gian hàng được duyệt trước khi product-service có queue (hoặc
 * khi RabbitMQ mất sự kiện) sẽ không bao giờ đăng bán được, dù shop-service ghi
 * {@code ACTIVE}. Job này đẩy ảnh chụp mọi gian hàng đã duyệt (ACTIVE / SUSPENDED) sang
 * {@code POST /api/products/internal/shop-access/reconcile} khi khởi động và định kỳ.
 * Product-service bỏ qua ảnh chụp cũ hơn dữ liệu nó đang có nên gửi lặp lại là an toàn.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.catalog-sync", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ShopCatalogAccessReconciler {

	/** Giới hạn của ShopAccessSyncRequest bên product-service. */
	static final int BATCH_SIZE = 500;
	private static final List<ShopStatus> APPROVED = List.of(ShopStatus.ACTIVE, ShopStatus.SUSPENDED);

	private final ShopRepository shopRepository;
	private final ProductServiceClient productServiceClient;

	public ShopCatalogAccessReconciler(ShopRepository shopRepository, ProductServiceClient productServiceClient) {
		this.shopRepository = shopRepository;
		this.productServiceClient = productServiceClient;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		reconcile();
	}

	@Scheduled(initialDelayString = "${app.catalog-sync.initial-delay:PT1M}",
			fixedDelayString = "${app.catalog-sync.interval:PT15M}")
	public void scheduled() {
		reconcile();
	}

	/** @return số gian hàng đã đồng bộ thành công */
	public int reconcile() {
		int synced = 0;
		int page = 0;
		Page<Shop> batch;
		do {
			batch = shopRepository.findByStatusIn(APPROVED,
					PageRequest.of(page, BATCH_SIZE, Sort.by("createdAt", "id")));
			List<ShopAccessSnapshot> snapshots = batch.getContent().stream()
					.filter(shop -> shop.getOwnerId() != null)
					.map(shop -> new ShopAccessSnapshot(shop.getId(), shop.getOwnerId(),
							shop.getStatus() == ShopStatus.ACTIVE,
							shop.getUpdatedAt() != null ? shop.getUpdatedAt() : shop.getCreatedAt(),
							shop.getName()))
					.toList();
			if (!snapshots.isEmpty()) {
				if (!productServiceClient.reconcileShopAccess(snapshots)) {
					log.warn("Dừng đối soát quyền đăng bán: product-service chưa nhận được (trang {})", page);
					return synced;
				}
				synced += snapshots.size();
			}
			page++;
		} while (batch.hasNext());
		if (synced > 0) {
			log.info("Đã đối soát quyền đăng bán của {} gian hàng sang product-service", synced);
		}
		return synced;
	}
}
