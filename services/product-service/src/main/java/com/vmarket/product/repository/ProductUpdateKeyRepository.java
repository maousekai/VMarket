package com.vmarket.product.repository;

import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.vmarket.product.model.ProductUpdateKey;

public interface ProductUpdateKeyRepository extends MongoRepository<ProductUpdateKey, String> {
	Optional<ProductUpdateKey> findByScopedKey(String scopedKey);
}
