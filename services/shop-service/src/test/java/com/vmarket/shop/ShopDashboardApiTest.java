package com.vmarket.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import com.vmarket.shop.client.OrderServiceClient.OrderStatsView;
import com.vmarket.shop.client.OrderServiceClient.RecentOrderView;
import com.vmarket.shop.client.ProductServiceClient.ProductSnapshot;
import com.vmarket.shop.client.UserServiceClient.UserSummary;
import com.vmarket.shop.exception.ApiException;

class ShopDashboardApiTest extends ShopApiTestSupport {

	private String activeShop() throws Exception {
		String shopId = register(OWNER_A, "Tiệm Gốm Dashboard");
		adminAction(shopId, "approve");
		return shopId;
	}

	private OrderStatsView statsView() {
		return new OrderStatsView(
				Instant.parse("2026-10-26T17:00:00Z"),
				Instant.parse("2026-11-02T10:00:00Z"),
				new OrderStatsView.Summary(new BigDecimal("1250000.00"), new BigDecimal("400000.00"), 8, 2),
				List.of(
						new OrderStatsView.StatusCount("PENDING", 1),
						new OrderStatsView.StatusCount("WAITING_PAYMENT", 0),
						new OrderStatsView.StatusCount("PROCESSING", 2),
						new OrderStatsView.StatusCount("SHIPPED", 1),
						new OrderStatsView.StatusCount("DELIVERED", 2),
						new OrderStatsView.StatusCount("CANCELLED", 2)),
				List.of(new OrderStatsView.DailyPoint(LocalDate.of(2026, 11, 1),
						new BigDecimal("300000.00"), 3)),
				List.of(new OrderStatsView.TopProduct("p1", 4, new BigDecimal("800000.00"))));
	}

	@Test
	void statsRequireActiveShopAndReturnOrderAndProductData() throws Exception {
		String shopId = register(OWNER_A, "Tiệm Gốm Dashboard");

		mockMvc.perform(get("/api/shops/me/stats")
						.header(HttpHeaders.AUTHORIZATION, buyer(OWNER_A)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("SHOP_NOT_ACTIVE"));

		adminAction(shopId, "approve");
		when(orderServiceClient.stats(eq(shopId), any(Instant.class), any(Instant.class)))
				.thenReturn(statsView());
		when(productServiceClient.snapshot("p1"))
				.thenReturn(new ProductSnapshot("p1", "Bình gốm men lam",
						List.of("https://cdn.test/p1.jpg"), 7L));
		when(productServiceClient.activeProductCount(shopId)).thenReturn(12L);

		mockMvc.perform(get("/api/shops/me/stats")
						.param("range", "7d")
						.header(HttpHeaders.AUTHORIZATION, buyer(OWNER_A)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.range").value("7d"))
				.andExpect(jsonPath("$.summary.grossRevenue").value(1250000.00))
				.andExpect(jsonPath("$.summary.deliveredRevenue").value(400000.00))
				.andExpect(jsonPath("$.summary.totalOrders").value(8))
				.andExpect(jsonPath("$.summary.cancelledOrders").value(2))
				.andExpect(jsonPath("$.ordersByStatus.length()").value(6))
				.andExpect(jsonPath("$.daily[0].date").value("2026-11-01"))
				.andExpect(jsonPath("$.topProducts[0].productId").value("p1"))
				.andExpect(jsonPath("$.topProducts[0].name").value("Bình gốm men lam"))
				.andExpect(jsonPath("$.topProducts[0].imageUrl").value("https://cdn.test/p1.jpg"))
				.andExpect(jsonPath("$.topProducts[0].quantity").value(4))
				.andExpect(jsonPath("$.activeProducts").value(12));

		verify(orderServiceClient).stats(eq(shopId), any(Instant.class), any(Instant.class));
	}

	@Test
	void statsRejectInvalidRangeAndMapOrderServiceFailureTo503() throws Exception {
		String shopId = activeShop();

		mockMvc.perform(get("/api/shops/me/stats")
						.param("range", "1y")
						.header(HttpHeaders.AUTHORIZATION, buyer(OWNER_A)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_RANGE"));

		when(orderServiceClient.stats(eq(shopId), any(Instant.class), any(Instant.class)))
				.thenThrow(new ApiException("STATS_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
						"Thống kê đơn hàng tạm thời không khả dụng"));

		mockMvc.perform(get("/api/shops/me/stats")
						.header(HttpHeaders.AUTHORIZATION, buyer(OWNER_A)))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("STATS_UNAVAILABLE"));
	}

	@Test
	void recentOrdersProxyReturnsShopScopedRowsAndValidatesLimit() throws Exception {
		String shopId = activeShop();
		when(orderServiceClient.recent(shopId, 5)).thenReturn(List.of(
				new RecentOrderView("o1", "DELIVERED", Instant.parse("2026-11-01T09:00:00Z"),
						new BigDecimal("250000.00"), 2)));

		mockMvc.perform(get("/api/shops/me/recent-orders")
						.header(HttpHeaders.AUTHORIZATION, buyer(OWNER_A)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].orderId").value("o1"))
				.andExpect(jsonPath("$[0].status").value("DELIVERED"))
				.andExpect(jsonPath("$[0].shopSubtotal").value(250000.00))
				.andExpect(jsonPath("$[0].itemCount").value(2));

		mockMvc.perform(get("/api/shops/me/recent-orders")
						.param("limit", "21")
						.header(HttpHeaders.AUTHORIZATION, buyer(OWNER_A)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void adminCountsIncludeEveryStatusAndTotal() throws Exception {
		register(OWNER_A, "Shop Pending");
		String active = register("01JBQ9YDX7K3M8N5P2R4T6V8W2", "Shop Active");
		adminAction(active, "approve");
		String rejected = register("01JBQ9YDX7K3M8N5P2R4T6V8W3", "Shop Rejected");
		adminAction(rejected, "reject", "Thiếu giấy phép");
		String suspended = register("01JBQ9YDX7K3M8N5P2R4T6V8W4", "Shop Suspended");
		adminAction(suspended, "approve");
		adminAction(suspended, "suspend", "Vi phạm nội dung");

		mockMvc.perform(get("/api/shops/admin/counts")
						.header(HttpHeaders.AUTHORIZATION, admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.pending").value(1))
				.andExpect(jsonPath("$.active").value(1))
				.andExpect(jsonPath("$.rejected").value(1))
				.andExpect(jsonPath("$.suspended").value(1))
				.andExpect(jsonPath("$.total").value(4));
	}

	@Test
	void adminListAndHistoryAreEnrichedWithOwnerAndActorNames() throws Exception {
		String shopId = register(OWNER_A, "Tiệm Gốm Enrich");
		when(userServiceClient.summaries(anyCollection())).thenReturn(Map.of(
				OWNER_A, new UserSummary(OWNER_A, "Nguyễn Văn An", "https://cdn.test/an.jpg"),
				ADMIN, new UserSummary(ADMIN, "Quản trị viên", null)));

		mockMvc.perform(get("/api/shops/admin")
						.param("status", "PENDING")
						.header(HttpHeaders.AUTHORIZATION, admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].ownerName").value("Nguyễn Văn An"))
				.andExpect(jsonPath("$.items[0].ownerAvatarUrl").value("https://cdn.test/an.jpg"));

		adminAction(shopId, "approve");

		mockMvc.perform(get("/api/shops/admin/{id}/status-history", shopId)
						.header(HttpHeaders.AUTHORIZATION, admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].actorName").value("Nguyễn Văn An"))
				.andExpect(jsonPath("$[1].actorName").value("Quản trị viên"));
	}

	@Test
	void enrichmentFailureDegradesToNullInsteadOfFailingTheList() throws Exception {
		register(OWNER_A, "Tiệm Gốm No Profile");
		when(userServiceClient.summaries(anyCollection())).thenThrow(new RuntimeException("down"));

		mockMvc.perform(get("/api/shops/admin")
						.header(HttpHeaders.AUTHORIZATION, admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].ownerName").doesNotExist());
	}

	@Test
	void sellerCannotReadAnotherSellersStatsByGuessingShopId() throws Exception {
		activeShop();
		// /me luôn tra theo ownerId trong token, không có tham số shopId để đoán.
		mockMvc.perform(get("/api/shops/me/stats")
						.header(HttpHeaders.AUTHORIZATION, buyer(OWNER_B)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("SHOP_NOT_FOUND"));
		assertThat(shopRepository.count()).isEqualTo(1);
	}
}
