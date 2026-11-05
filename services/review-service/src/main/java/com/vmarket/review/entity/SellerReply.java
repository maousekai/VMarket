package com.vmarket.review.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Phản hồi công khai của seller cho một đánh giá — mỗi đánh giá tối đa một phản hồi (FR-REV-03). */
@Entity
@Table(name = "seller_replies")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerReply {

	@Id
	@Column(length = 36)
	private String id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "review_id", nullable = false, unique = true)
	private Review review;

	@Column(name = "seller_id", nullable = false, length = 64)
	private String sellerId;

	@Column(nullable = false, length = 1000)
	private String content;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public SellerReply(Review review, String sellerId, String content, Instant now) {
		this.id = UUID.randomUUID().toString();
		this.review = review;
		this.sellerId = sellerId;
		this.content = content;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public void edit(String sellerId, String content, Instant now) {
		this.sellerId = sellerId;
		this.content = content;
		this.updatedAt = now;
	}
}
