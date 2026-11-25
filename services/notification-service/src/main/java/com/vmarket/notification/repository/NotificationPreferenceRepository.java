package com.vmarket.notification.repository;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.vmarket.notification.domain.NotificationPreference;

public interface NotificationPreferenceRepository extends MongoRepository<NotificationPreference, String> {
}
