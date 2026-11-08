package com.vmarket.events;

/**
 * Review Service phát aggregate mới nhất sau khi ghi nhận đánh giá (hoặc admin ẩn/khôi phục).
 * Dùng aggregate thay cho phép cộng cục bộ để consumer xử lý lặp idempotent.
 *
 * @param revision số phiên bản tăng dần theo {@code productId}, cấp dưới khoá DB của Review
 *                 Service. Consumer chỉ áp dụng bản tin có {@code revision} lớn hơn bản đã lưu —
 *                 KHÔNG so timestamp envelope, vì timestamp lấy từ đồng hồ của từng replica và có
 *                 thể lệch/lùi. Kiểu {@code Long} để bản tin cũ (chưa có trường này) vẫn
 *                 deserialize được — Jackson 3 từ chối {@code null} cho {@code long}; thiếu thì
 *                 chuẩn hoá về {@code 0}.
 */
public record ReviewCreated(String reviewId, String productId, double ratingAverage, long ratingCount,
		Long revision) {

	public ReviewCreated {
		revision = revision == null ? 0L : revision;
	}
}
