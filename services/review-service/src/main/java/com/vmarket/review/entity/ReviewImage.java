package com.vmarket.review.entity;

import com.github.f4b6a3.ulid.UlidCreator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Ảnh đính kèm đánh giá — tối đa 5 ảnh, {@code sortOrder} 0..4 (FR-REV-01). */
@Entity
@Table(name = "review_images")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewImage {

	public static final int MAX_PER_REVIEW = 5;

	@Id
	@Column(length = 26)
	private String id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "review_id", nullable = false)
	private Review review;

	@Column(nullable = false, length = 1000)
	private String url;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	ReviewImage(Review review, String url, int sortOrder) {
		this.id = UlidCreator.getUlid().toString();
		this.review = review;
		this.url = url;
		this.sortOrder = sortOrder;
	}
}
