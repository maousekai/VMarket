package com.vmarket.auth.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.vmarket.auth.entity.RefreshToken;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {

	Optional<RefreshToken> findByTokenHash(String tokenHash);

	/**
	 * Thu hồi token nếu nó còn hiệu lực. Trả về số dòng bị sửa (0 = đã bị thu hồi
	 * bởi request khác → xử lý như race/reuse).
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update RefreshToken rt
			   set rt.revokedAt = :now, rt.replacedBy = :replacedBy
			 where rt.id = :id and rt.revokedAt is null
			""")
	int revokeIfActive(@Param("id") String id, @Param("now") Instant now, @Param("replacedBy") String replacedBy);

	/** Thu hồi toàn bộ refresh token còn hiệu lực của một user (phát hiện reuse / khoá tài khoản). */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update RefreshToken rt set rt.revokedAt = :now where rt.userId = :userId and rt.revokedAt is null")
	int revokeAllActiveByUserId(@Param("userId") String userId, @Param("now") Instant now);
}
