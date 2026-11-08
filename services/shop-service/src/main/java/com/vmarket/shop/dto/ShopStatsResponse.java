package com.vmarket.shop.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Thống kê seller dashboard, đã enrich tên/ảnh sản phẩm bán chạy. */
public record ShopStatsResponse(
		String range,
		Instant from,
		Instant to,
		Summary summary,
		List<StatusCount> ordersByStatus,
		List<DailyPoint> daily,
		List<TopProduct> topProducts,
		Long activeProducts) {

	public record Summary(BigDecimal grossRevenue, BigDecimal deliveredRevenue,
			long totalOrders, long cancelledOrders) {
	}

	public record StatusCount(String status, long count) {
	}

	public record DailyPoint(LocalDate date, BigDecimal revenue, long orders) {
	}

	public record TopProduct(String productId, String name, String imageUrl,
			long quantity, BigDecimal revenue) {
	}
}
