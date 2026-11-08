package com.vmarket.review.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.review.dto.RatingSummary;
import com.vmarket.review.repository.ReviewRepository;

import lombok.RequiredArgsConstructor;

/**
 * Tổng hợp điểm đánh giá (FR-REV-02).
 *
 * <p>Tính trực tiếp từ bảng {@code reviews} bằng một câu {@code group by} có index thay vì giữ bộ
 * đếm cộng dồn: không bao giờ lệch khi admin ẩn/khôi phục đánh giá hay khi có ghi đồng thời.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RatingService {

	private final ReviewRepository reviews;

	public RatingSummary productSummary(String productId) {
		return RatingSummary.fromDistribution(productId, reviews.productDistribution(productId));
	}

	public RatingSummary shopSummary(String shopId) {
		return RatingSummary.fromDistribution(shopId, reviews.shopDistribution(shopId));
	}
}
