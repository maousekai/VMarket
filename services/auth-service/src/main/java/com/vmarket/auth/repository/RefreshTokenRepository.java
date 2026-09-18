package com.vmarket.auth.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.vmarket.auth.entity.RefreshToken;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {

	Optional<RefreshToken> findByTokenHash(String tokenHash);

	/**
	 * Danh sách phiên ĐANG HOẠT ĐỘNG THẬT SỰ của một user (FR-AUTH-06): chưa thu hồi
	 * VÀ chưa hết hạn — token hết hạn dù chưa có ai revoke cũng không dùng được nữa
	 * nên không nên hiện như một phiên sống. Sắp theo lần dùng gần nhất, dùng
	 * {@code COALESCE(lastUsedAt, createdAt)} vì {@code lastUsedAt} có thể NULL với
	 * dòng do instance cũ tạo trong lúc rolling deploy (xem migration V6).
	 */
	@Query("""
			select rt from RefreshToken rt
			 where rt.userId = :userId and rt.revokedAt is null and rt.expiresAt > :now
			 order by coalesce(rt.lastUsedAt, rt.createdAt) desc
			""")
	List<RefreshToken> findActiveByUserId(@Param("userId") String userId, @Param("now") Instant now);

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

	/**
	 * Thu hồi MỘT phiên cụ thể, chỉ khi thuộc đúng user gọi (chống revoke phiên
	 * của người khác dù đoán được id) VÀ còn hiệu lực thật sự (chưa hết hạn — nhất
	 * quán với {@link #findActiveByUserId}). Trả về số dòng bị sửa (0 = không tồn
	 * tại / không thuộc user này / đã bị thu hồi / đã hết hạn).
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update RefreshToken rt
			   set rt.revokedAt = :now
			 where rt.id = :id and rt.userId = :userId and rt.revokedAt is null and rt.expiresAt > :now
			""")
	int revokeIfActiveAndOwnedBy(@Param("id") String id, @Param("userId") String userId, @Param("now") Instant now);

	/** Thu hồi mọi phiên khác ĐANG HOẠT ĐỘNG THẬT SỰ của user, GIỮ LẠI phiên hiện tại. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update RefreshToken rt
			   set rt.revokedAt = :now
			 where rt.userId = :userId and rt.id <> :currentId and rt.revokedAt is null and rt.expiresAt > :now
			""")
	int revokeAllActiveByUserIdExcept(
			@Param("userId") String userId, @Param("currentId") String currentId, @Param("now") Instant now);
}
