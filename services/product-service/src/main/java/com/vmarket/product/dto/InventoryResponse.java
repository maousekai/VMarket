package com.vmarket.product.dto;

import java.util.List;

import com.vmarket.product.model.ReservationStatus;

public record InventoryResponse(String orderId, ReservationStatus status, List<InventoryRequest.InventoryItem> items) {
}
