package com.vmarket.product.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.product.exception.ApiException;
import com.vmarket.product.model.Product;
import com.vmarket.product.repository.ProductRepository;
import com.vmarket.product.security.InternalApiKeyGuard;
import com.vmarket.product.service.ProductMapper;
import jakarta.servlet.http.HttpServletRequest;

/** Read-only maintenance export. Writers must be paused for offset pagination. */
@RestController
@RequestMapping("/api/products/internal/search-snapshots")
public class SearchSnapshotController {

	private final ProductRepository products;
	private final MongoTemplate mongo;
	private final InternalApiKeyGuard guard;
	private final ProductMapper mapper;

	public SearchSnapshotController(ProductRepository products, MongoTemplate mongo,
			InternalApiKeyGuard guard, ProductMapper mapper) {
		this.products = products;
		this.mongo = mongo;
		this.guard = guard;
		this.mapper = mapper;
	}

	@GetMapping("/{id}")
	public Map<String, Object> get(@PathVariable String id,
			@RequestHeader(value = "X-Internal-Api-Key", required = false) String key,
			HttpServletRequest request) {
		guard.requireValid(key);
		validateParameters(request, Set.of());
		if (id.isBlank() || id.length() > 128) throw invalidQuery();
		return snapshot(products.findById(id).orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Product does not exist")));
	}

	@GetMapping
	public Map<String, Object> page(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "100") int size,
			@RequestHeader(value = "X-Internal-Api-Key", required = false) String key,
			HttpServletRequest request) {
		guard.requireValid(key);
		validateParameters(request, Set.of("page", "size"));
		if (page < 0 || size < 1 || size > 100) throw invalidQuery();
		// Sort raw Mongo _id, not its outward String representation (ObjectId/string legacy rows).
		Query query = new Query().with(Sort.by(Sort.Direction.ASC, "_id"))
				.skip((long) page * size).limit(size + 1);
		List<Product> rows = mongo.find(query, Product.class);
		return Map.of("items", rows.stream().limit(size).map(this::snapshot).toList(),
				"page", page, "hasMore", rows.size() > size);
	}

	public Map<String, Object> snapshot(Product product) {
		if (product.getVersion() == null || product.getVersion() < 0) {
			throw new ApiException(HttpStatus.CONFLICT, "SNAPSHOT_VERSION_MISSING",
					"Run the Product-owned revision migration during maintenance");
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", product.getId());
		result.put("productVersion", product.getVersion());
		result.put("catalogVisible", product.isCatalogVisible());
		result.put("deleted", product.getDeletedAt() != null);
		result.put("deletedAt", product.getDeletedAt());
		if (product.getDeletedAt() != null) return result;
		var projection = mapper.toResponse(product);
		if (projection.variants().stream().anyMatch(v -> !"VND".equals(v.currency()) || v.price() < 0)) {
			throw new ApiException(HttpStatus.CONFLICT, "SNAPSHOT_INVALID_DATA", "Invalid catalog currency or price");
		}
		result.put("shopId", projection.shopId());
		result.put("name", projection.name());
		result.put("description", projection.description());
		result.put("imageUrls", projection.imageUrls());
		result.put("categoryId", projection.categoryId());
		result.put("categoryPath", product.getCategoryPath() == null ? List.of() : product.getCategoryPath());
		result.put("currency", projection.currency());
		List<Long> prices = projection.variants().stream().map(v -> v.price()).toList();
		result.put("variantPrices", prices);
		result.put("minPrice", prices.stream().min(Long::compareTo).orElse(null));
		result.put("maxPrice", prices.stream().max(Long::compareTo).orElse(null));
		result.put("availableStock", projection.availableStock());
		result.put("ratingAverage", projection.ratingAverage());
		result.put("ratingCount", projection.ratingCount());
		result.put("soldCount", projection.soldCount());
		result.put("createdAt", projection.createdAt());
		result.put("updatedAt", projection.updatedAt());
		return result;
	}

	private void validateParameters(HttpServletRequest request, Set<String> allowed) {
		request.getParameterMap().forEach((name, values) -> {
			if (!allowed.contains(name) || values.length != 1) throw invalidQuery();
		});
	}

	private ApiException invalidQuery() {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "Invalid snapshot parameters");
	}
}
