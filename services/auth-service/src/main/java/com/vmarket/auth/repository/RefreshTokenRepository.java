package com.vmarket.auth.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.auth.entity.RefreshToken;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {

	Optional<RefreshToken> findByTokenHash(String tokenHash);

	@Modifying
	@Transactional
	@Query("delete from RefreshToken rt where rt.userId = :userId")
	int deleteByUserId(String userId);
}
