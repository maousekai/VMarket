package com.vmarket.product.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document("inventory_reservations")
public class InventoryReservation {
	@Id
	private String id;
	@Indexed(unique = true)
	private String orderId;
	private List<ReservationItem> items = new ArrayList<>();
	private ReservationStatus status;
	private Instant createdAt;
	private Instant updatedAt;
	@Version
	private Long version;

	public InventoryReservation(String id, String orderId, List<ReservationItem> items,
			ReservationStatus status, Instant createdAt, Instant updatedAt) {
		this(id, orderId, items, status, createdAt, updatedAt, null);
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ReservationItem {
		private String productId;
		private String variantId;
		private int quantity;
	}
}
