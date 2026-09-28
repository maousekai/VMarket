package com.vmarket.events;

/**
 * Review Service phát aggregate mới nhất sau khi ghi nhận đánh giá.
 * Dùng aggregate thay cho phép cộng cục bộ để consumer xử lý lặp idempotent.
 */
public record ReviewCreated(String reviewId, String productId, double ratingAverage, long ratingCount) {
}
