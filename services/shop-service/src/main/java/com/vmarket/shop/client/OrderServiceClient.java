package com.vmarket.shop.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import com.vmarket.shop.exception.ApiException;

import lombok.extern.slf4j.Slf4j;

/** Đọc thống kê / đơn gần đây của một gian hàng từ order-service. */
@Slf4j
public class OrderServiceClient {

	private final RestClient restClient;

	public OrderServiceClient(RestClient restClient) {
		this.restClient = restClient;
	}

	public OrderStatsView stats(String shopId, Instant from, Instant to) {
		try {
			OrderStatsView view = restClient.get()
					.uri(builder -> builder.path("/internal/orders/shops/{shopId}/stats")
							.queryParam("from", from)
							.queryParam("to", to)
							.build(shopId))
					.retrieve()
					.body(OrderStatsView.class);
			if (view == null) {
				throw unavailable();
			}
			return view;
		} catch (ApiException ex) {
			throw ex;
		} catch (RuntimeException ex) {
			log.error("Không đọc được thống kê đơn hàng của shop {}: {}", shopId, ex.getMessage());
			throw unavailable();
		}
	}

	public List<RecentOrderView> recent(String shopId, int limit) {
		try {
			List<RecentOrderView> rows = restClient.get()
					.uri(builder -> builder.path("/internal/orders/shops/{shopId}/recent")
							.queryParam("limit", limit)
							.build(shopId))
					.retrieve()
					.body(new org.springframework.core.ParameterizedTypeReference<List<RecentOrderView>>() {
					});
			return rows == null ? List.of() : rows;
		} catch (RuntimeException ex) {
			log.error("Không đọc được đơn gần đây của shop {}: {}", shopId, ex.getMessage());
			throw new ApiException("RECENT_ORDERS_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
					"Thống kê đơn hàng tạm thời không khả dụng, vui lòng thử lại sau");
		}
	}

	private static ApiException unavailable() {
		return new ApiException("STATS_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
				"Thống kê đơn hàng tạm thời không khả dụng, vui lòng thử lại sau");
	}

	public record OrderStatsView(
			Instant from,
			Instant to,
			Summary summary,
			List<StatusCount> ordersByStatus,
			List<DailyPoint> daily,
			List<TopProduct> topProducts) {

		public record Summary(BigDecimal grossRevenue, BigDecimal deliveredRevenue,
				long totalOrders, long cancelledOrders) {
		}

		public record StatusCount(String status, long count) {
		}

		public record DailyPoint(LocalDate date, BigDecimal revenue, long orders) {
		}

		public record TopProduct(String productId, long quantity, BigDecimal revenue) {
		}
	}

	public record RecentOrderView(String orderId, String status, Instant createdAt,
			BigDecimal shopSubtotal, long itemCount) {
	}
}
