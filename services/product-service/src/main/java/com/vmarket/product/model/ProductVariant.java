package com.vmarket.product.model;

import java.util.LinkedHashMap;
import java.util.Map;

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
	/** VND minor units (VND has no fractional minor unit). */
	private long price;
	private String currency = "VND";
	private long stock;
	private long reservedStock;
	private long soldCount;

	public ProductVariant(String id, String sku, Map<String, String> attributes, long price,
			long stock, long reservedStock, long soldCount) {
		this(id, sku, attributes, price, "VND", stock, reservedStock, soldCount);
	}
}
