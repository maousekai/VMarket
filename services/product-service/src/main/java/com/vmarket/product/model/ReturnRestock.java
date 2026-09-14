package com.vmarket.product.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Bản ghi idempotency cho một lần nhập kho hàng trả. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document("return_restocks")
public class ReturnRestock {
	@Id
	private String id;
	@Indexed(unique = true)
	private String returnId;
	@Indexed
	private String orderId;
	private List<InventoryReservation.ReservationItem> items = new ArrayList<>();
	private Instant createdAt;
}
