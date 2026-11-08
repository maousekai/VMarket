package com.vmarket.order.stats;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.vmarket.order.config.InternalApiProperties;
import com.vmarket.order.entity.Order;
import com.vmarket.order.entity.OrderItem;
import com.vmarket.order.entity.OrderStatus;
import com.vmarket.order.exception.ApiException;
import com.vmarket.order.repository.OrderRepository;

import lombok.RequiredArgsConstructor;

/**
 * Thống kê đơn hàng theo gian hàng cho seller dashboard.
 *
 * <p>Tính bằng Java thay vì SQL {@code date_trunc}: cùng một câu JPQL chạy được
 * trên PostgreSQL (prod) và H2 (test), và mốc ngày luôn theo giờ Việt Nam bất kể
 * timezone của DB.
 */
@Service
@RequiredArgsConstructor
public class OrderStatsService {

	public static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
	private static final int MAX_RANGE_DAYS = 366;
	private static final int MAX_RECENT = 20;

	private final OrderRepository orders;
	private final InternalApiProperties properties;

	public void authorize(String key) {
		if (key == null
				|| !MessageDigest.isEqual(
						properties.getApiKey().getBytes(StandardCharsets.UTF_8),
						key.getBytes(StandardCharsets.UTF_8))) {
			throw new ApiException("UNAUTHORIZED", HttpStatus.UNAUTHORIZED, "Sai khoá nội bộ");
		}
	}

	public OrderShopStatsResponse stats(String shopId, Instant from, Instant to) {
		if (shopId == null || shopId.isBlank()) {
			throw ApiException.badRequest("INVALID_SHOP_ID", "Thiếu mã gian hàng");
		}
		if (from == null || to == null || !from.isBefore(to)) {
			throw ApiException.badRequest("INVALID_RANGE", "Khoảng thời gian không hợp lệ");
		}
		if (Duration.between(from, to).toDays() > MAX_RANGE_DAYS) {
			throw ApiException.badRequest("RANGE_TOO_LARGE", "Khoảng thời gian tối đa 366 ngày");
		}

		List<ShopOrderRow> rows = orders.findShopOrderRows(shopId, from, to);

		// Một đơn lặp lại theo số item: chốt trạng thái một lần cho mỗi orderId.
		Map<String, OrderStatus> statusByOrder = new LinkedHashMap<>();
		for (ShopOrderRow row : rows) {
			statusByOrder.putIfAbsent(row.getOrderId(), row.getStatus());
		}

		long[] counts = new long[OrderStatus.values().length];
		for (OrderStatus status : statusByOrder.values()) {
			counts[status.ordinal()]++;
		}

		BigDecimal grossRevenue = BigDecimal.ZERO;
		BigDecimal deliveredRevenue = BigDecimal.ZERO;
		Map<LocalDate, BigDecimal> revenueByDay = new LinkedHashMap<>();
		Map<LocalDate, Set<String>> ordersByDay = new LinkedHashMap<>();
		Map<String, TopProductAgg> topByProduct = new LinkedHashMap<>();

		for (ShopOrderRow row : rows) {
			OrderStatus status = statusByOrder.get(row.getOrderId());
			LocalDate date = row.getCreatedAt().atZone(VN).toLocalDate();
			ordersByDay.computeIfAbsent(date, ignored -> new HashSet<>()).add(row.getOrderId());

			if (status == OrderStatus.CANCELLED) {
				continue;
			}

			BigDecimal lineTotal = row.getLineTotal() == null ? BigDecimal.ZERO : row.getLineTotal();
			grossRevenue = grossRevenue.add(lineTotal);
			revenueByDay.merge(date, lineTotal, BigDecimal::add);
			if (status == OrderStatus.DELIVERED) {
				deliveredRevenue = deliveredRevenue.add(lineTotal);
			}

			TopProductAgg agg = topByProduct.computeIfAbsent(
					row.getProductId(), id -> new TopProductAgg(id));
			agg.quantity += row.getQuantity();
			agg.revenue = agg.revenue.add(lineTotal);
		}

		List<OrderShopStatsResponse.StatusCount> byStatus = new ArrayList<>();
		for (OrderStatus status : OrderStatus.values()) {
			byStatus.add(new OrderShopStatsResponse.StatusCount(status.name(), counts[status.ordinal()]));
		}

		List<OrderShopStatsResponse.DailyPoint> daily = new ArrayList<>();
		LocalDate start = from.atZone(VN).toLocalDate();
		LocalDate end = to.minusNanos(1).atZone(VN).toLocalDate();
		for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
			daily.add(new OrderShopStatsResponse.DailyPoint(
					date,
					revenueByDay.getOrDefault(date, BigDecimal.ZERO),
					ordersByDay.getOrDefault(date, Set.of()).size()));
		}

		List<OrderShopStatsResponse.TopProduct> topProducts = topByProduct.values().stream()
				.sorted(Comparator.comparingLong(TopProductAgg::quantity).reversed()
						.thenComparing(Comparator.comparing(TopProductAgg::revenue).reversed())
						.thenComparing(TopProductAgg::productId))
				.limit(5)
				.map(agg -> new OrderShopStatsResponse.TopProduct(agg.productId, agg.quantity, agg.revenue))
				.toList();

		return new OrderShopStatsResponse(
				from,
				to,
				new OrderShopStatsResponse.Summary(
						grossRevenue,
						deliveredRevenue,
						statusByOrder.size(),
						counts[OrderStatus.CANCELLED.ordinal()]),
				byStatus,
				daily,
				topProducts);
	}

	public List<OrderShopStatsResponse.RecentOrder> recent(String shopId, int limit) {
		if (shopId == null || shopId.isBlank()) {
			throw ApiException.badRequest("INVALID_SHOP_ID", "Thiếu mã gian hàng");
		}
		if (limit < 1 || limit > MAX_RECENT) {
			throw ApiException.badRequest("INVALID_LIMIT", "Số đơn gần đây phải từ 1 đến 20");
		}

		List<Order> recent = orders.findRecentForShop(shopId, PageRequest.of(0, limit));
		return recent.stream().map(order -> {
			BigDecimal subtotal = BigDecimal.ZERO;
			long itemCount = 0;
			for (OrderItem item : order.getItems()) {
				if (shopId.equals(item.getShopId())) {
					subtotal = subtotal.add(item.getLineTotal());
					itemCount += item.getQuantity();
				}
			}
			return new OrderShopStatsResponse.RecentOrder(
					order.getId(),
					order.getStatus().name(),
					order.getCreatedAt(),
					subtotal,
					itemCount);
		}).toList();
	}

	private static final class TopProductAgg {
		private final String productId;
		private long quantity;
		private BigDecimal revenue = BigDecimal.ZERO;

		private TopProductAgg(String productId) {
			this.productId = productId;
		}

		private long quantity() {
			return quantity;
		}

		private BigDecimal revenue() {
			return revenue;
		}

		private String productId() {
			return productId;
		}
	}
}
