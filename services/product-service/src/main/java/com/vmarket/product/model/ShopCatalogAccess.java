package com.vmarket.product.model;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Projection cục bộ của quyền sở hữu/trạng thái Shop, đồng bộ từ Shop events. */
@Data
@NoArgsConstructor
@Document("shop_catalog_access")
public class ShopCatalogAccess {
	@Id
	private String shopId;
	@Indexed
	private String sellerId;
	private boolean active;
	/** Timestamp nghiệp vụ do Shop Service cung cấp; dùng để bỏ qua event đến trễ. */
	private Instant updatedAt;
	/** Lần projection được bootstrap/reconcile gần nhất. */
	private Instant reconciledAt;

	public ShopCatalogAccess(String shopId, String sellerId, boolean active, Instant updatedAt) {
		this(shopId, sellerId, active, updatedAt, Instant.now());
	}

	public ShopCatalogAccess(String shopId, String sellerId, boolean active, Instant updatedAt, Instant reconciledAt) {
		this.shopId = shopId;
		this.sellerId = sellerId;
		this.active = active;
		this.updatedAt = updatedAt;
		this.reconciledAt = reconciledAt;
	}
}
