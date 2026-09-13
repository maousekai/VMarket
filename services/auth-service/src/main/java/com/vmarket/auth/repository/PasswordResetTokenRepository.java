package com.vmarket.auth.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.vmarket.auth.entity.PasswordResetToken;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, String> {

	Optional<PasswordResetToken> findFirstByUserIdOrderByCreatedAtDesc(String userId);

	/** Số mã đã phát cho một user kể từ {@code cutoff} (giới hạn tần suất theo giờ). */
	long countByUserIdAndCreatedAtAfter(String userId, Instant cutoff);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update PasswordResetToken t set t.attempts = t.attempts + 1 where t.id = :id")
	int incrementAttempts(@Param("id") String id);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update PasswordResetToken t set t.consumedAt = :now where t.id = :id and t.consumedAt is null")
	int markConsumed(@Param("id") String id, @Param("now") Instant now);

	@Modifying
	@Query("delete from PasswordResetToken t where t.createdAt < :cutoff")
	int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
