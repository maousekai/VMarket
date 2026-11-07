package com.vmarket.order.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.vmarket.order.entity.Order;

public record SellerOrderResponse(
		String id,
		String status,
		String recipientName,
		String phone,
		String province,
		String district,
		String ward,
		String streetAddress,
		String note,
		BigDecimal shopTotalAmount,
		boolean shopConfirmed,
		Instant createdAt,
		Instant updatedAt,
		String paymentMethod,
		List<SellerOrderItemResponse> items) {

	public static SellerOrderResponse from(Order order, String shopId) {
		List<SellerOrderItemResponse> shopItems = order.getItems().stream()
				.filter(i -> shopId.equals(i.getShopId()))
				.map(SellerOrderItemResponse::from)
				.toList();
		BigDecimal shopTotal = shopItems.stream()
				.map(SellerOrderItemResponse::lineTotal)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		boolean shopConfirmed = !shopItems.isEmpty() && shopItems.stream().allMatch(SellerOrderItemResponse::confirmed);

		return new SellerOrderResponse(
				order.getId(),
				order.getStatus().name(),
				order.getRecipientName(),
				order.getPhone(),
				order.getProvince(),
				order.getDistrict(),
				order.getWard(),
				order.getStreetAddress(),
				order.getNote(),
				shopTotal,
				shopConfirmed,
				order.getCreatedAt(),
				order.getUpdatedAt(),
				order.getPaymentMethod(),
				shopItems);
	}
}
