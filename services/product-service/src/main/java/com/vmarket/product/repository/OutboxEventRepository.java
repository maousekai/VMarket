package com.vmarket.product.repository;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.vmarket.product.model.OutboxEvent;

public interface OutboxEventRepository extends MongoRepository<OutboxEvent, String> {
}
