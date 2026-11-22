package com.vmarket.cart.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.vmarket.cart.dto.ProductSnapshot;
import com.vmarket.cart.exception.ProductCatalogUnavailableException;

/** product-service giả cho test: giá do test "niêm yết" (PBL6-48). */
class FakeProductCatalog implements ProductCatalogClient {

	private record Listing(String shopId, BigDecimal price, Map<String, BigDecimal> variants) {
	}

	private final Map<String, Listing> products = new HashMap<>();
	boolean down;

	/** Niêm yết giá; {@code variantId == null} nghĩa là sản phẩm một biến thể. */
	void list(String productId, String variantId, String shopId, BigDecimal price) {
		Listing current = products.get(productId);
		Map<String, BigDecimal> variants = current == null ? new HashMap<>() : new HashMap<>(current.variants());
		BigDecimal single = current == null ? null : current.price();
		if (variantId == null) {
			single = price;
		} else {
			variants.put(variantId, price);
		}
		products.put(productId, new Listing(shopId, single, variants));
	}

	@Override
	public Optional<ProductSnapshot> findProduct(String productId) {
		if (down) {
			throw new ProductCatalogUnavailableException("down", null);
		}
		Listing listing = products.get(productId);
		if (listing == null) {
			return Optional.empty();
		}
		List<ProductSnapshot.VariantSnapshot> variants = new ArrayList<>();
		listing.variants().forEach((id, price) -> variants.add(new ProductSnapshot.VariantSnapshot(id, price, 100)));
		return Optional.of(new ProductSnapshot(productId, listing.shopId(), listing.price(), 100, variants));
	}
}
