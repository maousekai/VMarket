package com.vmarket.notification.repository;

import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.vmarket.notification.domain.EmailLog;

public interface EmailLogRepository extends MongoRepository<EmailLog, String> {

	Optional<EmailLog> findByDedupKey(String dedupKey);
}
