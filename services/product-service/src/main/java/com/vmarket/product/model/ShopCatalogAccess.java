package com.vmarket.product.model;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Projection cục bộ của quyền sở hữu/trạng thái Shop, đồng bộ từ Shop events. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document("shop_catalog_access")
public class ShopCatalogAccess {
	@Id
	private String shopId;
	@Indexed
	private String sellerId;
	private boolean active;
	private Instant updatedAt;
}
