package com.vmarket.auth.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.vmarket.auth.entity.EmailOtp;

public interface EmailOtpRepository extends JpaRepository<EmailOtp, String> {

	Optional<EmailOtp> findFirstByEmailOrderByCreatedAtDesc(String email);

	/** Số OTP đã phát cho một email kể từ {@code cutoff} (giới hạn tần suất theo giờ). */
	long countByEmailAndCreatedAtAfter(String email, Instant cutoff);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update EmailOtp o set o.attempts = o.attempts + 1 where o.id = :id")
	int incrementAttempts(@Param("id") String id);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update EmailOtp o set o.consumedAt = :now where o.id = :id and o.consumedAt is null")
	int markConsumed(@Param("id") String id, @Param("now") Instant now);

	@Modifying
	@Query("delete from EmailOtp o where o.createdAt < :cutoff")
	int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
