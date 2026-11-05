package com.vmarket.review.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.BatchSize;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một đánh giá của người mua cho một sản phẩm trong một đơn (FR-REV-01).
 *
 * <p>Ràng buộc {@code UNIQUE (order_id, product_id)} ở DB bảo đảm mỗi sản phẩm trong đơn chỉ được
 * đánh giá một lần kể cả khi hai request tới cùng lúc.
 */
@Entity
@Table(name = "reviews")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Review {

	@Id
	@Column(length = 36)
	private String id;

	@Column(name = "order_id", nullable = false, length = 36)
	private String orderId;

	@Column(name = "product_id", nullable = false, length = 64)
	private String productId;

	@Column(name = "variant_id", length = 64)
	private String variantId;

	@Column(name = "shop_id", nullable = false, length = 64)
	private String shopId;

	@Column(name = "buyer_id", nullable = false, length = 64)
	private String buyerId;

	@Column(nullable = false)
	private int rating;

	@Column(length = 2000)
	private String comment;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ReviewStatus status;

	@Column(name = "moderation_reason", length = 500)
	private String moderationReason;

	@Column(name = "moderated_by", length = 64)
	private String moderatedBy;

	@Column(name = "moderated_at")
	private Instant moderatedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	@Column(nullable = false)
	private long version;

	@OneToMany(mappedBy = "review", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
	@OrderBy("sortOrder ASC")
	@BatchSize(size = 50)
	private List<ReviewImage> images = new ArrayList<>();

	@OneToOne(mappedBy = "review", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
	private SellerReply reply;

	public static Review create(String orderId, String productId, String variantId, String shopId, String buyerId,
			int rating, String comment, List<String> imageUrls, Instant now) {
		Review review = new Review();
		review.id = UUID.randomUUID().toString();
		review.orderId = orderId;
		review.productId = productId;
		review.variantId = variantId;
		review.shopId = shopId;
		review.buyerId = buyerId;
		review.rating = rating;
		review.comment = comment;
		review.status = ReviewStatus.VISIBLE;
		review.createdAt = now;
		review.updatedAt = now;
		for (int i = 0; i < imageUrls.size(); i++) {
			review.images.add(new ReviewImage(review, imageUrls.get(i), i));
		}
		return review;
	}

	public boolean isVisible() {
		return status == ReviewStatus.VISIBLE;
	}
}
