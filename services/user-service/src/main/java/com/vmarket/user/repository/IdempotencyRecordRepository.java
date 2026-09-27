package com.vmarket.user.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.vmarket.user.entity.IdempotencyRecord;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {

	Optional<IdempotencyRecord> findByUserIdAndIdempotencyKey(String userId, String idempotencyKey);

	/**
	 * Dọn các dòng đã quá hạn giữ.
	 *
	 * <p>Bảng này chỉ có tác dụng trong vài giây đến vài phút sau request gốc (client
	 * retry), nhưng lại ghi thêm một dòng cho MỖI request có header — không dọn thì
	 * nó phình vô hạn.
	 */
	@Modifying
	@Query("delete from IdempotencyRecord r where r.createdAt < :before")
	int deleteCreatedBefore(@Param("before") Instant before);
}
