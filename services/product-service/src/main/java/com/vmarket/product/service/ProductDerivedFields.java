package com.vmarket.product.service;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.vmarket.product.model.Product;
import com.vmarket.product.model.ProductVariant;

@Component
public class ProductDerivedFields {
	public void refresh(Product product) {
		product.setMinPrice(product.getVariants().stream().map(ProductVariant::getPrice)
				.min(BigDecimal::compareTo).orElse(null));
		product.setMaxPrice(product.getVariants().stream().map(ProductVariant::getPrice)
				.max(BigDecimal::compareTo).orElse(null));
		product.setAvailableStock(product.getVariants().stream()
				.mapToLong(variant -> Math.max(0, variant.getStock() - variant.getReservedStock())).sum());
	}
}
