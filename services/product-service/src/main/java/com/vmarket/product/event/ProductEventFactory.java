package com.vmarket.product.event;

import java.math.BigDecimal;
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
		return new ProductCreated(product.getId(), product.getShopId(), product.getName(), minPrice(product),
				product.getStatus().name(), copyImages(product), 3, product.getDescription(),
				product.getCategoryId(), product.getBrandId(), maxPrice(product), variants(product),
				product.getRatingAverage(), product.getRatingCount(), product.getSoldCount(), availableStock(product),
				catalogVisible(product));
	}

	public ProductUpdated updated(Product product) {
		return new ProductUpdated(product.getId(), product.getShopId(), product.getName(), minPrice(product),
				product.getStatus().name(), copyImages(product), 3, product.getDescription(),
				product.getCategoryId(), product.getBrandId(), maxPrice(product), variants(product),
				product.getRatingAverage(), product.getRatingCount(), product.getSoldCount(), availableStock(product),
				catalogVisible(product));
	}

	private List<ProductVariantSnapshot> variants(Product product) {
		return product.getVariants() == null ? List.of() : product.getVariants().stream().map(this::variant).toList();
	}

	private List<String> copyImages(Product product) {
		return product.getImageUrls() == null ? List.of() : List.copyOf(product.getImageUrls());
	}

	private BigDecimal minPrice(Product product) {
		return product.getMinPrice() != null ? product.getMinPrice() : safeVariants(product).stream()
				.map(ProductVariant::getPrice).min(BigDecimal::compareTo).orElse(null);
	}

	private BigDecimal maxPrice(Product product) {
		return product.getMaxPrice() != null ? product.getMaxPrice() : safeVariants(product).stream()
				.map(ProductVariant::getPrice).max(BigDecimal::compareTo).orElse(null);
	}

	private long availableStock(Product product) {
		long calculated = safeVariants(product).stream()
				.mapToLong(item -> Math.max(0, item.getStock() - item.getReservedStock())).sum();
		return product.getAvailableStock() == 0 && calculated > 0 ? calculated : product.getAvailableStock();
	}

	private List<ProductVariant> safeVariants(Product product) {
		return product.getVariants() == null ? List.of() : product.getVariants();
	}

	private boolean catalogVisible(Product product) {
		return product.getDeletedAt() == null && product.getStatus() == com.vmarket.product.model.ProductStatus.ACTIVE
				&& !product.isModerationRemoved() && !product.isShopSuspended() && product.isCategoryVisible();
	}

	private ProductVariantSnapshot variant(ProductVariant variant) {
		return new ProductVariantSnapshot(variant.getId(), variant.getSku(),
				variant.getAttributes() == null ? Map.of() : Map.copyOf(variant.getAttributes()), variant.getPrice(),
				Math.max(0, variant.getStock() - variant.getReservedStock()), variant.getSoldCount());
	}
}
