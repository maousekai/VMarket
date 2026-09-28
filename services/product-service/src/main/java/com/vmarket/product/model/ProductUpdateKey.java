package com.vmarket.product.model;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Idempotency record committed atomically with one product update. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document("product_update_keys")
public class ProductUpdateKey {
	@Id
	private String id;
	@Indexed(unique = true)
	private String scopedKey;
	private String productId;
	private String requestHash;
	@Indexed(expireAfter = "7d")
	private Instant createdAt;
}
