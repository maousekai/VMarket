package com.vmarket.product.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.vmarket.product.model.Product;

public interface ProductRepository extends MongoRepository<Product, String> {
	List<Product> findAllBySellerIdAndDeletedAtIsNullOrderByUpdatedAtDesc(String sellerId);
	List<Product> findAllByShopIdAndDeletedAtIsNull(String shopId);
	List<Product> findAllByDeletedAtIsNullOrderByUpdatedAtDesc();
	boolean existsByCategoryIdAndDeletedAtIsNull(String categoryId);
	boolean existsByBrandIdAndDeletedAtIsNull(String brandId);
}
