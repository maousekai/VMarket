package com.vmarket.product.repository;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.vmarket.product.model.ShopCatalogAccess;

public interface ShopCatalogAccessRepository extends MongoRepository<ShopCatalogAccess, String> {
}
