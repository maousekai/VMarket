package com.vmarket.product.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.vmarket.product.model.OutboxEvent;

public interface OutboxEventRepository extends MongoRepository<OutboxEvent, String> {
	List<OutboxEvent> findTop50ByPublishedAtIsNullAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(Instant now);
}
