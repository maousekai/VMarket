package com.vmarket.product.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.vmarket.product.model.Category;
import com.vmarket.product.model.Product;
import com.vmarket.product.model.ProductStatus;
import com.vmarket.product.repository.CategoryRepository;
import com.vmarket.product.repository.ProductRepository;

/** Maintains materialized category/shop/derived projections without unbounded transactions. */
@Service
public class CatalogProjectionService {
	private static final String FIRST_ID = "";
	private final CategoryRepository categoryRepository;
	private final ProductRepository productRepository;
	private final ProductDerivedFields derivedFields;
	private final CatalogProjectionBatchWriter batchWriter;

	public CatalogProjectionService(CategoryRepository categoryRepository, ProductRepository productRepository,
			ProductDerivedFields derivedFields, CatalogProjectionBatchWriter batchWriter) {
		this.categoryRepository = categoryRepository;
		this.productRepository = productRepository;
		this.derivedFields = derivedFields;
		this.batchWriter = batchWriter;
	}

	public CategoryProjection categoryProjection(String categoryId) {
		List<String> path = new ArrayList<>();
		Set<String> visited = new HashSet<>();
		String cursor = categoryId;
		boolean visible = true;
		while (cursor != null && visited.add(cursor)) {
			Category category = categoryRepository.findById(cursor).orElse(null);
			if (category == null) {
				visible = false;
				break;
			}
			path.add(category.getId());
			visible &= category.isActive();
			cursor = category.getParentId();
		}
		if (cursor != null) visible = false;
		return new CategoryProjection(List.copyOf(path), visible);
	}

	public void applyCategory(Product product) {
		CategoryProjection projection = categoryProjection(product.getCategoryId());
		product.setCategoryPath(projection.path());
		product.setCategoryVisible(projection.visible());
	}

	public void synchronizeCategorySubtree(String rootCategoryId) {
		Set<String> visited = new HashSet<>();
		ArrayDeque<String> categories = new ArrayDeque<>();
		categories.add(rootCategoryId);
		while (!categories.isEmpty()) {
			String categoryId = categories.removeFirst();
			if (!visited.add(categoryId)) continue;
			synchronizeCategoryProducts(categoryId);
			categoryRepository.findByParentId(categoryId)
					.forEach(child -> categories.addLast(child.getId()));
		}
	}

	public void synchronizeShopProducts(String shopId, boolean active) {
		String afterId = FIRST_ID;
		while (true) {
			List<Product> batch = productRepository
					.findTop100ByShopIdAndDeletedAtIsNullAndIdGreaterThanOrderByIdAsc(shopId, afterId);
			if (batch.isEmpty()) return;
			List<Product> changed = new ArrayList<>();
			for (Product product : batch) {
				if (applyShopState(product, active)) changed.add(product);
			}
			batchWriter.saveAndPublish(changed);
			afterId = batch.get(batch.size() - 1).getId();
		}
	}

	public void synchronizeAllProducts() {
		String afterId = FIRST_ID;
		while (true) {
			List<Product> batch = productRepository
					.findTop100ByDeletedAtIsNullAndIdGreaterThanOrderByIdAsc(afterId);
			if (batch.isEmpty()) return;
			List<Product> changed = new ArrayList<>();
			for (Product product : batch) {
				BigDecimal oldMin = product.getMinPrice();
				BigDecimal oldMax = product.getMaxPrice();
				long oldStock = product.getAvailableStock();
				boolean categoryChanged = refreshCategory(product);
				derivedFields.refresh(product);
				if (categoryChanged || !Objects.equals(oldMin, product.getMinPrice())
						|| !Objects.equals(oldMax, product.getMaxPrice()) || oldStock != product.getAvailableStock()) {
					product.setUpdatedAt(Instant.now());
					changed.add(product);
				}
			}
			batchWriter.saveAndPublish(changed);
			afterId = batch.get(batch.size() - 1).getId();
		}
	}

	private boolean refreshCategory(Product product) {
		List<String> oldPath = product.getCategoryPath() == null ? List.of() : product.getCategoryPath();
		boolean oldVisible = product.isCategoryVisible();
		applyCategory(product);
		boolean changed = !oldPath.equals(product.getCategoryPath()) || oldVisible != product.isCategoryVisible();
		if (changed) product.setUpdatedAt(Instant.now());
		return changed;
	}

	private boolean applyShopState(Product product, boolean active) {
		if (!active) {
			if (product.isShopSuspended()) return false;
			ProductStatus desired = product.isModerationRemoved() && product.getStatusBeforeModeration() != null
					? product.getStatusBeforeModeration() : product.getStatus();
			product.setStatusBeforeShopSuspension(desired);
			product.setShopSuspended(true);
			product.setStatus(ProductStatus.HIDDEN);
			product.setUpdatedAt(Instant.now());
			return true;
		}
		if (!product.isShopSuspended()) return false;
		ProductStatus desired = product.getStatusBeforeShopSuspension() == null
				? ProductStatus.DRAFT : product.getStatusBeforeShopSuspension();
		product.setShopSuspended(false);
		if (product.isModerationRemoved()) {
			product.setStatusBeforeModeration(desired);
			product.setStatus(ProductStatus.HIDDEN);
		} else {
			product.setStatus(desired);
		}
		product.setStatusBeforeShopSuspension(null);
		product.setUpdatedAt(Instant.now());
		return true;
	}

	private void synchronizeCategoryProducts(String categoryId) {
		String afterId = FIRST_ID;
		while (true) {
			List<Product> batch = productRepository
					.findTop100ByCategoryIdAndDeletedAtIsNullAndIdGreaterThanOrderByIdAsc(categoryId, afterId);
			if (batch.isEmpty()) return;
			List<Product> changed = new ArrayList<>();
			for (Product product : batch) {
				if (refreshCategory(product)) changed.add(product);
			}
			batchWriter.saveAndPublish(changed);
			afterId = batch.get(batch.size() - 1).getId();
		}
	}

	public record CategoryProjection(List<String> path, boolean visible) {
	}
}
