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
@Document("categories")
public class Category {
	@Id
	private String id;
	private String name;
	@Indexed(unique = true)
	private String slug;
	@Indexed
	private String parentId;
	private boolean active;
	private int sortOrder;
	private Instant createdAt;
	private Instant updatedAt;
}
