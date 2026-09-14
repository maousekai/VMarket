package com.vmarket.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.vmarket.product.dto.CategoryRequest;
import com.vmarket.product.exception.ApiException;
import com.vmarket.product.model.Category;
import com.vmarket.product.repository.CategoryRepository;
import com.vmarket.product.repository.ProductRepository;
import com.vmarket.product.service.CategoryService;
import com.vmarket.product.service.SlugService;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {
	@Mock CategoryRepository categoryRepository;
	@Mock ProductRepository productRepository;
	private CategoryService service;

	@BeforeEach
	void setUp() {
		service = new CategoryService(categoryRepository, productRepository, new SlugService());
	}

	@Test
	void createGeneratesVietnameseSlug() {
		when(categoryRepository.save(any())).thenAnswer(invocation -> {
			Category category = invocation.getArgument(0);
			category.setId("cat-1");
			return category;
		});

		var result = service.create(new CategoryRequest("Điện thoại & Phụ kiện", null, 2, true));

		assertThat(result.slug()).isEqualTo("dien-thoai-phu-kien");
		assertThat(result.sortOrder()).isEqualTo(2);
	}

	@Test
	void updateRejectsCategoryCycle() {
		Category parent = category("parent", "child");
		Category child = category("child", null);
		when(categoryRepository.findById("child")).thenReturn(Optional.of(child));
		when(categoryRepository.findById("parent")).thenReturn(Optional.of(parent));

		assertThatThrownBy(() -> service.update("child", new CategoryRequest("Child", "parent", 0, true)))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("chu trình");
	}

	@Test
	void treeBuildsMultipleLevelsInSortOrder() {
		Category root = category("root", null);
		Category child = category("child", "root");
		when(categoryRepository.findAllByOrderBySortOrderAscNameAsc()).thenReturn(List.of(root, child));

		var tree = service.tree(false);

		assertThat(tree).hasSize(1);
		assertThat(tree.get(0).children()).extracting("id").containsExactly("child");
	}

	@Test
	void publicTreeDoesNotPromoteActiveChildOfHiddenParent() {
		Category root = category("root", null);
		root.setActive(false);
		Category child = category("child", "root");
		when(categoryRepository.findAllByOrderBySortOrderAscNameAsc()).thenReturn(List.of(root, child));

		assertThat(service.tree(false)).isEmpty();
	}

	@Test
	void descendantsStopAtHiddenCategoryBranch() {
		Category root = category("root", null);
		Category hidden = category("hidden", "root");
		hidden.setActive(false);
		Category leakedChild = category("leaked", "hidden");
		when(categoryRepository.findById("root")).thenReturn(Optional.of(root));
		when(categoryRepository.findAllByOrderBySortOrderAscNameAsc())
				.thenReturn(List.of(root, hidden, leakedChild));

		assertThat(service.descendantIds("root")).containsExactly("root");
	}

	@Test
	void deleteRejectsCategoryUsedByProduct() {
		Category category = category("cat-1", null);
		when(categoryRepository.findById("cat-1")).thenReturn(Optional.of(category));
		when(categoryRepository.findByParentId("cat-1")).thenReturn(List.of());
		when(productRepository.existsByCategoryIdAndDeletedAtIsNull("cat-1")).thenReturn(true);

		assertThatThrownBy(() -> service.delete("cat-1")).isInstanceOf(ApiException.class)
				.hasMessageContaining("sản phẩm sử dụng");
		verify(categoryRepository, never()).delete(any());
	}

	private Category category(String id, String parentId) {
		return new Category(id, id, id, parentId, true, 0, Instant.now(), Instant.now());
	}
}
