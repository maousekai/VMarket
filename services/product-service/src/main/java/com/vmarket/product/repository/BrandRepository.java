package com.vmarket.product.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.vmarket.product.model.Brand;

public interface BrandRepository extends MongoRepository<Brand, String> {
	boolean existsBySlug(String slug);
	boolean existsBySlugAndIdNot(String slug, String id);
	List<Brand> findAllByOrderByNameAsc();
}
