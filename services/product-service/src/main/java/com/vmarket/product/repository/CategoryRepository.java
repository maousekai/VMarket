package com.vmarket.product.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.vmarket.product.model.Category;

public interface CategoryRepository extends MongoRepository<Category, String> {
	boolean existsBySlug(String slug);
	boolean existsBySlugAndIdNot(String slug, String id);
	List<Category> findAllByOrderBySortOrderAscNameAsc();
	List<Category> findByParentId(String parentId);
}
