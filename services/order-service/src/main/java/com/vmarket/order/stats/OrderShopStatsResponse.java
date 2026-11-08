package com.vmarket.order.stats;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Dữ liệu thống kê thô của một gian hàng cho shop-service.
 *
 * <p>Không chứa tên/ảnh sản phẩm: order-service không sở hữu dữ liệu catalog,
 * shop-service sẽ enrich qua product-service.
 */
public record OrderShopStatsResponse(
		Instant from,
		Instant to,
		Summary summary,
		List<StatusCount> ordersByStatus,
		List<DailyPoint> daily,
		List<TopProduct> topProducts) {

	public record Summary(
			BigDecimal grossRevenue,
			BigDecimal deliveredRevenue,
			long totalOrders,
			long cancelledOrders) {
	}

	public record StatusCount(String status, long count) {
	}

	public record DailyPoint(LocalDate date, BigDecimal revenue, long orders) {
	}

	public record TopProduct(String productId, long quantity, BigDecimal revenue) {
	}

	public record RecentOrder(
			String orderId,
			String status,
			Instant createdAt,
			BigDecimal shopSubtotal,
			long itemCount) {
	}
}
