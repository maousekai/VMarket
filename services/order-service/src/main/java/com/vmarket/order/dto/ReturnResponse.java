package com.vmarket.order.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import com.vmarket.order.entity.OrderReturn;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

public record ReturnResponse(
		String id,
		String orderId,
		String userId,
		String shopId,
		String status,
		String reason,
		List<String> evidenceUrls,
		String sellerNote,
		String adminNote,
		BigDecimal refundAmount,
		Instant createdAt,
		Instant updatedAt,
		Instant resolvedAt,
		List<ReturnItemResponse> items) {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static ReturnResponse from(OrderReturn r) {
		List<String> urls = Collections.emptyList();
		if (r.getEvidenceUrls() != null && !r.getEvidenceUrls().isBlank()) {
			try {
				urls = MAPPER.readValue(r.getEvidenceUrls(), new TypeReference<List<String>>() {});
			} catch (Exception ignored) {
				urls = List.of(r.getEvidenceUrls());
			}
		}

		return new ReturnResponse(
				r.getId(),
				r.getOrderId(),
				r.getUserId(),
				r.getShopId(),
				r.getStatus().name(),
				r.getReason(),
				urls,
				r.getSellerNote(),
				r.getAdminNote(),
				r.getRefundAmount(),
				r.getCreatedAt(),
				r.getUpdatedAt(),
				r.getResolvedAt(),
				r.getItems().stream().map(ReturnItemResponse::from).toList());
	}
}
