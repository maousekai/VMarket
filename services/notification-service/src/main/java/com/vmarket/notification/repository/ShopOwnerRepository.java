package com.vmarket.notification.repository;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.vmarket.notification.domain.ShopOwner;

public interface ShopOwnerRepository extends MongoRepository<ShopOwner, String> {
}
