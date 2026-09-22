package com.vmarket.product.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document("products")
@CompoundIndex(name = "catalog_filter_idx", def = "{'status': 1, 'categoryId': 1, 'shopId': 1}")
public class Product {
	@Id
	private String id;
	@Indexed
	private String shopId;
	@Indexed
	private String sellerId;
	@Indexed
	private String name;
	private String description;
	private List<String> imageUrls = new ArrayList<>();
	@Indexed
	private String categoryId;
	private List<String> categoryPath = new ArrayList<>();
	@Indexed
	private boolean categoryVisible = true;
	private String brandId;
	@Indexed
	private ProductStatus status;
	private List<ProductVariant> variants = new ArrayList<>();
	@Indexed
	private Long minPrice;
	private Long maxPrice;
	private long availableStock;
	private double ratingAverage;
	private long ratingCount;
	private Instant ratingUpdatedAt;
	private long soldCount;
	private boolean moderationRemoved;
	private String moderationReason;
	private Instant moderationResubmittedAt;
	private ProductStatus statusBeforeModeration;
	private boolean shopSuspended;
	private ProductStatus statusBeforeShopSuspension;
	private Instant createdAt;
	private Instant updatedAt;
	private Instant deletedAt;
	@Indexed(unique = true, sparse = true)
	private String creationKey;
	private String creationRequestHash;
	@Version
	private Long version;

	public boolean isCatalogVisible() {
		return deletedAt == null && status == ProductStatus.ACTIVE && !moderationRemoved
				&& !shopSuspended && categoryVisible;
	}

	public ProductStatus getUnrestrictedStatus() {
		if (shopSuspended && statusBeforeShopSuspension != null) return statusBeforeShopSuspension;
		if (moderationRemoved && statusBeforeModeration != null) return statusBeforeModeration;
		return (moderationRemoved || shopSuspended) && status == ProductStatus.HIDDEN ? ProductStatus.DRAFT : status;
	}

	public void updateDesiredStatus(ProductStatus desired) {
		if (moderationRemoved) statusBeforeModeration = desired;
		if (shopSuspended) statusBeforeShopSuspension = desired;
		status = moderationRemoved || shopSuspended ? ProductStatus.HIDDEN : desired;
	}

	public void applyModerationRestriction(boolean removed) {
		if (moderationRemoved == removed) return;
		ProductStatus desired = getUnrestrictedStatus();
		moderationRemoved = removed;
		statusBeforeModeration = removed ? desired : null;
		updateDesiredStatus(desired);
	}

	public boolean applyShopSuspension(boolean suspended) {
		if (shopSuspended == suspended) return false;
		ProductStatus desired = getUnrestrictedStatus();
		shopSuspended = suspended;
		statusBeforeShopSuspension = suspended ? desired : null;
		updateDesiredStatus(desired);
		return true;
	}
}
