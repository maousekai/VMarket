package com.vmarket.product.model;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductVariant {
	private String id;
	private String sku;
	private Map<String, String> attributes = new LinkedHashMap<>();
	@Field(targetType = FieldType.DECIMAL128)
	private BigDecimal price;
	private long stock;
	private long reservedStock;
	private long soldCount;
}
