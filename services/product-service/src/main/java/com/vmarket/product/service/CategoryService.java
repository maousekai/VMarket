package com.vmarket.product.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.vmarket.product.dto.CategoryRequest;
import com.vmarket.product.dto.CategoryResponse;
import com.vmarket.product.exception.ApiException;
import com.vmarket.product.model.Category;
import com.vmarket.product.repository.CategoryRepository;
import com.vmarket.product.repository.ProductRepository;

@Service
public class CategoryService {
	private final CategoryRepository categoryRepository;
	private final ProductRepository productRepository;
	private final SlugService slugService;

	public CategoryService(CategoryRepository categoryRepository, ProductRepository productRepository, SlugService slugService) {
		this.categoryRepository = categoryRepository;
		this.productRepository = productRepository;
		this.slugService = slugService;
	}

	public CategoryResponse create(CategoryRequest request) {
		validateParent(request.parentId(), null);
		String slug = slugService.slugify(request.name());
		if (categoryRepository.existsBySlug(slug)) {
			throw conflict("Danh mục có tên tương ứng đã tồn tại");
		}
		Instant now = Instant.now();
		Category category = new Category(null, request.name().trim(), slug, blankToNull(request.parentId()),
				request.active(), request.sortOrder(), now, now);
		return toResponse(categoryRepository.save(category), List.of());
	}

	public CategoryResponse update(String id, CategoryRequest request) {
		Category category = getRequired(id);
		validateParent(request.parentId(), id);
		String slug = slugService.slugify(request.name());
		if (categoryRepository.existsBySlugAndIdNot(slug, id)) {
			throw conflict("Danh mục có tên tương ứng đã tồn tại");
		}
		category.setName(request.name().trim());
		category.setSlug(slug);
		category.setParentId(blankToNull(request.parentId()));
		category.setSortOrder(request.sortOrder());
		category.setActive(request.active());
		category.setUpdatedAt(Instant.now());
		return toResponse(categoryRepository.save(category), List.of());
	}

	public CategoryResponse setVisibility(String id, boolean active) {
		Category category = getRequired(id);
		category.setActive(active);
		category.setUpdatedAt(Instant.now());
		return toResponse(categoryRepository.save(category), List.of());
	}

	public void delete(String id) {
		Category category = getRequired(id);
		if (!categoryRepository.findByParentId(id).isEmpty()) {
			throw conflict("Không thể xóa danh mục đang có danh mục con");
		}
		if (productRepository.existsByCategoryIdAndDeletedAtIsNull(id)) {
			throw conflict("Không thể xóa danh mục đang được sản phẩm sử dụng; hãy ẩn danh mục thay thế");
		}
		categoryRepository.delete(category);
	}

	public List<CategoryResponse> tree(boolean includeInactive) {
		List<Category> categories = categoryRepository.findAllByOrderBySortOrderAscNameAsc().stream()
				.filter(category -> includeInactive || category.isActive()).toList();
		Map<String, List<Category>> children = new HashMap<>();
		for (Category category : categories) {
			children.computeIfAbsent(category.getParentId(), ignored -> new ArrayList<>()).add(category);
		}
		Set<String> visibleIds = new HashSet<>();
		categories.forEach(category -> visibleIds.add(category.getId()));
		return categories.stream()
				.filter(category -> category.getParentId() == null || !visibleIds.contains(category.getParentId()))
				.map(category -> buildTree(category, children, new HashSet<>())).toList();
	}

	public List<String> descendantIds(String categoryId) {
		if (categoryId == null || categoryId.isBlank()) return List.of();
		getRequired(categoryId);
		List<String> ids = new ArrayList<>();
		collectDescendants(categoryId, ids, new HashSet<>());
		return ids;
	}

	public Category getRequired(String id) {
		return categoryRepository.findById(id)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", "Không tìm thấy danh mục"));
	}

	private void validateParent(String parentId, String currentId) {
		if (parentId == null || parentId.isBlank()) return;
		if (parentId.equals(currentId)) throw conflict("Danh mục không thể là cha của chính nó");
		getRequired(parentId);
		String cursor = parentId;
		Set<String> visited = new HashSet<>();
		while (cursor != null && visited.add(cursor)) {
			if (cursor.equals(currentId)) throw conflict("Quan hệ danh mục tạo thành chu trình");
			cursor = categoryRepository.findById(cursor).map(Category::getParentId).orElse(null);
		}
	}

	private CategoryResponse buildTree(Category category, Map<String, List<Category>> children, Set<String> path) {
		if (!path.add(category.getId())) return toResponse(category, List.of());
		List<CategoryResponse> nested = children.getOrDefault(category.getId(), List.of()).stream()
				.map(child -> buildTree(child, children, new HashSet<>(path))).toList();
		return toResponse(category, nested);
	}

	private void collectDescendants(String id, List<String> result, Set<String> visited) {
		if (!visited.add(id)) return;
		result.add(id);
		categoryRepository.findByParentId(id).forEach(child -> collectDescendants(child.getId(), result, visited));
	}

	private CategoryResponse toResponse(Category category, List<CategoryResponse> children) {
		return new CategoryResponse(category.getId(), category.getName(), category.getSlug(), category.getParentId(),
				category.isActive(), category.getSortOrder(), children);
	}

	private ApiException conflict(String message) {
		return new ApiException(HttpStatus.CONFLICT, "CATEGORY_CONFLICT", message);
	}

	private String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}
}
