package com.vmarket.review.service;

import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.vmarket.review.client.ReviewClients;
import com.vmarket.review.config.ReviewProperties;
import com.vmarket.review.service.ReturnedOrderService.LegacyReturn;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Đối chiếu dữ liệu trả hàng ghi theo kiểu cũ (PBL6-35, migration V3).
 *
 * <p>Bản đầu chỉ ghi đơn nào đã trả hàng ({@code legacy_returned_orders}), không ghi sản phẩm. Không suy
 * ra được từ đánh giá, nên job hỏi Order Service các sản phẩm trong yêu cầu trả hàng {@code RESOLVED} của
 * từng đơn rồi chuyển sang {@code returned_items}. Order Service chưa trả lời được thì giữ nguyên dòng
 * (cả đơn vẫn bị chặn) và thử lại ở lần chạy sau.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyReturnReconciler {

	static final int BATCH = 50;

	private final ReturnedOrderService returnedOrders;
	private final ReviewClients clients;
	private final ReviewProperties properties;

	@Scheduled(initialDelayString = "${app.review.legacy-return-initial-delay:30000}",
			fixedDelayString = "${app.review.legacy-return-delay:300000}")
	public void reconcile() {
		List<LegacyReturn> pending = returnedOrders.legacyReturns(BATCH);
		if (pending.isEmpty()) {
			return;
		}
		if (properties.getInternalApiKey() == null || properties.getInternalApiKey().isBlank()) {
			log.warn("Còn {} đơn trả hàng kiểu cũ chưa đối chiếu nhưng thiếu INTERNAL_API_KEY: các đơn này vẫn bị "
					+ "chặn cả đơn", pending.size());
			return;
		}
		for (LegacyReturn legacy : pending) {
			try {
				returnedOrders.reconcileLegacy(legacy, clients.returnedProducts(legacy.orderId()));
			} catch (RuntimeException ex) {
				log.warn("Chưa đối chiếu được đơn trả hàng cũ {}: {}", legacy.orderId(), ex.getMessage());
			}
		}
	}
}
