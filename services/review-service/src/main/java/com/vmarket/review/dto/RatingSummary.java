package com.vmarket.review.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Điểm tổng hợp của một sản phẩm hoặc gian hàng (FR-REV-02). Chỉ tính đánh giá đang hiển thị.
 *
 * @param average      trung bình cộng số sao, 0 khi chưa có đánh giá
 * @param count        số lượt đánh giá
 * @param distribution số lượt theo từng mức sao, luôn đủ khoá 1..5
 */
@Schema(name = "RatingSummary", description = "Điểm đánh giá tổng hợp")
public record RatingSummary(
		@Schema(example = "01JBQ9YDX7K3M8N5P2R4T6V8W1") String targetId,
		@Schema(example = "4.5") double average,
		@Schema(example = "12") long count,
		@Schema(example = "{\"1\":0,\"2\":1,\"3\":1,\"4\":2,\"5\":8}") Map<Integer, Long> distribution) {

	/** Dựng từ kết quả {@code group by rating}; trung bình làm tròn 2 chữ số để client hiển thị ổn định. */
	public static RatingSummary fromDistribution(String targetId, List<Object[]> rows) {
		Map<Integer, Long> distribution = new LinkedHashMap<>();
		for (int star = 1; star <= 5; star++) {
			distribution.put(star, 0L);
		}
		long count = 0;
		long sum = 0;
		for (Object[] row : rows) {
			int star = ((Number) row[0]).intValue();
			long n = ((Number) row[1]).longValue();
			distribution.put(star, n);
			count += n;
			sum += star * n;
		}
		double average = count == 0 ? 0
				: BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP).doubleValue();
		return new RatingSummary(targetId, average, count, distribution);
	}
}
