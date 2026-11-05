package com.vmarket.notification.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import com.vmarket.notification.domain.Notification;

public interface NotificationRepository extends MongoRepository<Notification, String> {

	Page<Notification> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

	Page<Notification> findByUserIdAndReadFalseOrderByCreatedAtDesc(String userId, Pageable pageable);

	long countByUserIdAndReadFalse(String userId);

	boolean existsByDedupKey(String dedupKey);
}
