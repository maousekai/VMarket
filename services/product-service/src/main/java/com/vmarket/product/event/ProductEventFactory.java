package com.vmarket.product.event;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.vmarket.events.ProductCreated;
import com.vmarket.events.ProductUpdated;
import com.vmarket.events.ProductVariantSnapshot;
import com.vmarket.product.model.Product;
import com.vmarket.product.model.ProductVariant;

@Component
public class ProductEventFactory {
	public ProductCreated created(Product product) {
		return new ProductCreated(product.getId(), product.getShopId(), product.getName(), product.getMinPrice(),
				product.getStatus().name(), List.copyOf(product.getImageUrls()), 2, product.getDescription(),
				product.getCategoryId(), product.getBrandId(), product.getMaxPrice(), variants(product),
				product.getRatingAverage(), product.getRatingCount(), product.getSoldCount(), product.getAvailableStock());
	}

	public ProductUpdated updated(Product product) {
		return new ProductUpdated(product.getId(), product.getShopId(), product.getName(), product.getMinPrice(),
				product.getStatus().name(), List.copyOf(product.getImageUrls()), 2, product.getDescription(),
				product.getCategoryId(), product.getBrandId(), product.getMaxPrice(), variants(product),
				product.getRatingAverage(), product.getRatingCount(), product.getSoldCount(), product.getAvailableStock());
	}

	private List<ProductVariantSnapshot> variants(Product product) {
		return product.getVariants().stream().map(this::variant).toList();
	}

	private ProductVariantSnapshot variant(ProductVariant variant) {
		return new ProductVariantSnapshot(variant.getId(), variant.getSku(),
				variant.getAttributes() == null ? Map.of() : Map.copyOf(variant.getAttributes()), variant.getPrice(),
				Math.max(0, variant.getStock() - variant.getReservedStock()), variant.getSoldCount());
	}
}
