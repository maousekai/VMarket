package com.vmarket.product.service;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Component;

import com.vmarket.product.model.Product;
import com.vmarket.product.model.ProductVariant;

@Component
public class ProductDerivedFields {
	public void refresh(Product product) {
		List<ProductVariant> variants = product.getVariants() == null ? List.of() : product.getVariants();
		product.setMinPrice(variants.stream().map(ProductVariant::getPrice)
				.min(BigDecimal::compareTo).orElse(null));
		product.setMaxPrice(variants.stream().map(ProductVariant::getPrice)
				.max(BigDecimal::compareTo).orElse(null));
		product.setAvailableStock(variants.stream()
				.mapToLong(variant -> Math.max(0, variant.getStock() - variant.getReservedStock())).sum());
	}
}
