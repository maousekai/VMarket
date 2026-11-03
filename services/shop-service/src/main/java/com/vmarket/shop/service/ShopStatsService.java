package com.vmarket.shop.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.shop.client.OrderServiceClient;
import com.vmarket.shop.client.OrderServiceClient.OrderStatsView;
import com.vmarket.shop.client.ProductServiceClient;
import com.vmarket.shop.client.ProductServiceClient.ProductSnapshot;
import com.vmarket.shop.dto.ShopRecentOrderResponse;
import com.vmarket.shop.dto.ShopStatsResponse;
import com.vmarket.shop.entity.Shop;
import com.vmarket.shop.entity.ShopStatus;
import com.vmarket.shop.exception.ApiException;
import com.vmarket.shop.repository.ShopRepository;

import lombok.RequiredArgsConstructor;

/** Thống kê seller dashboard: gộp order-service và product-service. */
@Service
@RequiredArgsConstructor
public class ShopStatsService {

	private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
	private static final int MAX_RECENT = 20;

	private final ShopRepository shopRepository;
	private final OrderServiceClient orderServiceClient;
	private final ProductServiceClient productServiceClient;

	@Transactional(readOnly = true)
	public ShopStatsResponse stats(String ownerId, String range) {
		Shop shop = activeShop(ownerId);
		int days = parseRange(range);

		Instant to = Instant.now();
		LocalDate today = to.atZone(VN).toLocalDate();
		Instant from = today.minusDays(days - 1L).atStartOfDay(VN).toInstant();

		OrderStatsView raw = orderServiceClient.stats(shop.getId(), from, to);
		List<ShopStatsResponse.TopProduct> topProducts = raw.topProducts().stream()
				.map(row -> {
					ProductSnapshot snapshot = productServiceClient.snapshot(row.productId());
					String name = snapshot == null ? null : snapshot.name();
					String imageUrl = snapshot == null || snapshot.imageUrls() == null
							|| snapshot.imageUrls().isEmpty() ? null : snapshot.imageUrls().get(0);
					return new ShopStatsResponse.TopProduct(row.productId(), name, imageUrl,
							row.quantity(), row.revenue());
				})
				.toList();

		return new ShopStatsResponse(
				range,
				raw.from(),
				raw.to(),
				new ShopStatsResponse.Summary(raw.summary().grossRevenue(), raw.summary().deliveredRevenue(),
						raw.summary().totalOrders(), raw.summary().cancelledOrders()),
				raw.ordersByStatus().stream()
						.map(row -> new ShopStatsResponse.StatusCount(row.status(), row.count()))
						.toList(),
				raw.daily().stream()
						.map(row -> new ShopStatsResponse.DailyPoint(row.date(), row.revenue(), row.orders()))
						.toList(),
				topProducts,
				productServiceClient.activeProductCount(shop.getId()));
	}

	@Transactional(readOnly = true)
	public List<ShopRecentOrderResponse> recentOrders(String ownerId, int limit) {
		Shop shop = activeShop(ownerId);
		if (limit < 1 || limit > MAX_RECENT) {
			throw ApiException.badRequest("INVALID_LIMIT", "Số đơn gần đây phải từ 1 đến 20");
		}
		return orderServiceClient.recent(shop.getId(), limit).stream()
				.map(row -> new ShopRecentOrderResponse(row.orderId(), row.status(), row.createdAt(),
						row.shopSubtotal(), row.itemCount()))
				.toList();
	}

	private Shop activeShop(String ownerId) {
		Shop shop = shopRepository.findByOwnerId(ownerId)
				.orElseThrow(() -> ApiException.notFound("SHOP_NOT_FOUND", "Bạn chưa đăng ký gian hàng"));
		if (shop.getStatus() != ShopStatus.ACTIVE) {
			throw ApiException.conflict("SHOP_NOT_ACTIVE",
					"Gian hàng chưa hoạt động, dashboard chưa có dữ liệu");
		}
		return shop;
	}

	private static int parseRange(String range) {
		if (range == null) {
			return 7;
		}
		return switch (range) {
			case "7d" -> 7;
			case "30d" -> 30;
			case "90d" -> 90;
			default -> throw ApiException.badRequest("INVALID_RANGE", "range chỉ nhận 7d, 30d hoặc 90d");
		};
	}
}
