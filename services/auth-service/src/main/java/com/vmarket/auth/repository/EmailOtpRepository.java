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

	/**
	 * Tăng bộ đếm sai NGUYÊN TỬ, có điều kiện {@code attempts < maxAttempts}. 0 dòng
	 * = giới hạn đã đạt (có thể do request song song khác vừa tăng) → gọi phải coi
	 * là "hết lượt", không dựa vào giá trị {@code attempts} đọc trước đó (có thể cũ).
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update EmailOtp o set o.attempts = o.attempts + 1 "
			+ "where o.id = :id and o.attempts < :maxAttempts")
	int incrementAttempts(@Param("id") String id, @Param("maxAttempts") int maxAttempts);

	/**
	 * Đánh dấu đã dùng NGUYÊN TỬ, có điều kiện {@code consumed_at is null AND
	 * attempts < maxAttempts}. Điều kiện thứ hai chặn race: request mã đúng đã đọc
	 * {@code attempts} cũ (trước khi các request mã sai khác commit) vẫn không thể
	 * consume nếu giới hạn đã đạt ở thời điểm UPDATE thực thi.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update EmailOtp o set o.consumedAt = :now "
			+ "where o.id = :id and o.consumedAt is null and o.attempts < :maxAttempts")
	int markConsumed(@Param("id") String id, @Param("now") Instant now, @Param("maxAttempts") int maxAttempts);

	@Modifying
	@Query("delete from EmailOtp o where o.createdAt < :cutoff")
	int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
