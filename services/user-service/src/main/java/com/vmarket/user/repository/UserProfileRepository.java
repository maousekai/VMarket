package com.vmarket.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.vmarket.user.entity.UserProfile;

public interface UserProfileRepository extends JpaRepository<UserProfile, String> {

	Optional<UserProfile> findByUserId(String userId);
}
