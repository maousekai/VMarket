package com.vmarket.product.service;

import java.util.List;

import org.springframework.stereotype.Component;

import com.vmarket.product.dto.ProductResponse;
import com.vmarket.product.model.Product;
import com.vmarket.product.model.ProductVariant;

@Component
public class ProductMapper {
	public ProductResponse toResponse(Product product) {
		List<ProductResponse.VariantResponse> variants = product.getVariants().stream().map(this::toVariantResponse).toList();
		Long minPrice = product.getMinPrice() != null ? product.getMinPrice()
				: product.getVariants().stream().map(ProductVariant::getPrice).min(Long::compareTo).orElse(null);
		Long maxPrice = product.getMaxPrice() != null ? product.getMaxPrice()
				: product.getVariants().stream().map(ProductVariant::getPrice).max(Long::compareTo).orElse(null);
		long calculatedAvailable = product.getVariants().stream()
				.mapToLong(v -> Math.max(0, v.getStock() - v.getReservedStock())).sum();
		long available = product.getAvailableStock() == 0 && calculatedAvailable > 0
				? calculatedAvailable : product.getAvailableStock();
		return new ProductResponse(product.getId(), product.getShopId(), product.getName(), product.getDescription(),
				List.copyOf(product.getImageUrls()), product.getCategoryId(), product.getBrandId(), product.getStatus(), "VND", variants,
				minPrice, maxPrice, available, product.getRatingAverage(), product.getRatingCount(), product.getSoldCount(),
				product.isModerationRemoved(), product.getModerationReason(), product.getCreatedAt(), product.getUpdatedAt());
	}

	private ProductResponse.VariantResponse toVariantResponse(ProductVariant variant) {
		return new ProductResponse.VariantResponse(variant.getId(), variant.getSku(), MapCopy.copy(variant.getAttributes()),
				variant.getPrice(), currency(variant), variant.getStock(), variant.getReservedStock(),
				Math.max(0, variant.getStock() - variant.getReservedStock()), variant.getSoldCount());
	}

	private String currency(ProductVariant variant) {
		return variant.getCurrency() == null ? "VND" : variant.getCurrency();
	}

	private static final class MapCopy {
		private static <K, V> java.util.Map<K, V> copy(java.util.Map<K, V> value) {
			return value == null ? java.util.Map.of() : java.util.Map.copyOf(value);
		}
	}
}
