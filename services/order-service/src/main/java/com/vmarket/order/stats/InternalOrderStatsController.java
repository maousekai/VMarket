package com.vmarket.order.stats;

import java.time.Instant;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/** Endpoint nội bộ cho shop-service đọc thống kê đơn hàng của một gian hàng. */
@RestController
@RequestMapping("/internal/orders/shops")
@RequiredArgsConstructor
public class InternalOrderStatsController {

	private final OrderStatsService service;

	@GetMapping("/{shopId}/stats")
	public OrderShopStatsResponse stats(
			@PathVariable String shopId,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
			@RequestHeader(value = "X-Internal-Api-Key", required = false) String key) {
		service.authorize(key);
		return service.stats(shopId, from, to);
	}

	@GetMapping("/{shopId}/recent")
	public List<OrderShopStatsResponse.RecentOrder> recent(
			@PathVariable String shopId,
			@RequestParam(defaultValue = "5") int limit,
			@RequestHeader(value = "X-Internal-Api-Key", required = false) String key) {
		service.authorize(key);
		return service.recent(shopId, limit);
	}
}
