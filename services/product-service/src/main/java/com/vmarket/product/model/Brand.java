package com.vmarket.product.model;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document("brands")
public class Brand {
	@Id
	private String id;
	private String name;
	@Indexed(unique = true)
	private String slug;
	private boolean active;
	private Instant createdAt;
	private Instant updatedAt;
}
