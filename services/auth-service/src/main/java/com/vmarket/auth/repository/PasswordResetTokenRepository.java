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

	/**
	 * Tăng bộ đếm sai NGUYÊN TỬ, có điều kiện {@code attempts < maxAttempts}. 0 dòng
	 * = giới hạn đã đạt (có thể do request song song khác vừa tăng) → gọi phải coi
	 * là "hết lượt", không dựa vào giá trị {@code attempts} đọc trước đó (có thể cũ).
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update PasswordResetToken t set t.attempts = t.attempts + 1 "
			+ "where t.id = :id and t.attempts < :maxAttempts")
	int incrementAttempts(@Param("id") String id, @Param("maxAttempts") int maxAttempts);

	/**
	 * Đánh dấu đã dùng NGUYÊN TỬ, có điều kiện {@code consumed_at is null AND
	 * attempts < maxAttempts}. Điều kiện thứ hai chặn đúng race: một request mã
	 * đúng đã đọc {@code attempts} cũ (trước khi các request mã sai khác commit)
	 * vẫn không thể consume nếu giới hạn đã đạt ở thời điểm UPDATE thực thi.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update PasswordResetToken t set t.consumedAt = :now "
			+ "where t.id = :id and t.consumedAt is null and t.attempts < :maxAttempts")
	int markConsumed(@Param("id") String id, @Param("now") Instant now, @Param("maxAttempts") int maxAttempts);

	@Modifying
	@Query("delete from PasswordResetToken t where t.createdAt < :cutoff")
	int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
