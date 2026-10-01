package com.vmarket.auth.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.vmarket.auth.entity.EmailOtpLock;

import jakarta.persistence.LockModeType;

public interface EmailOtpLockRepository extends JpaRepository<EmailOtpLock, String> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select l from EmailOtpLock l where l.email = :email")
	Optional<EmailOtpLock> findByEmailForUpdate(@Param("email") String email);

	/** Dọn dòng khoá của email không còn OTP nào (chạy sau khi đã dọn {@code email_otp}). */
	@Modifying
	@Query("delete from EmailOtpLock l where l.createdAt < :cutoff "
			+ "and not exists (select 1 from EmailOtp o where o.email = l.email)")
	int deleteUnusedBefore(@Param("cutoff") Instant cutoff);
}
