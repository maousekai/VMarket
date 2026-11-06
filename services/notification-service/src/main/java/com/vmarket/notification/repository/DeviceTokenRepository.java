package com.vmarket.notification.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.vmarket.notification.domain.DeviceToken;

public interface DeviceTokenRepository extends MongoRepository<DeviceToken, String> {

	List<DeviceToken> findByUserId(String userId);

	Optional<DeviceToken> findByToken(String token);

	long deleteByToken(String token);

	long deleteByTokenAndUserId(String token, String userId);
}
