package com.vmarket.auth.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.vmarket.auth.entity.User;

import jakarta.persistence.LockModeType;

/**
 * {@link JpaSpecificationExecutor} phục vụ tìm kiếm tài khoản cho Admin (FR-USER-04):
 * bộ lọc tuỳ chọn (từ khoá, trạng thái, danh sách id) ghép động — xem
 * {@code AccountAdminService}.
 */
public interface UserRepository extends JpaRepository<User, String>, JpaSpecificationExecutor<User> {

	Optional<User> findByEmail(String email);

	Optional<User> findByUsername(String username);

	boolean existsByEmail(String email);

	boolean existsByUsername(String username);

	/**
	 * Khoá dòng user (SELECT ... FOR UPDATE) trong transaction hiện tại — dùng để
	 * tuần tự hoá check-rồi-ghi (cooldown/hourly-limit + insert token) giữa các
	 * request song song cho cùng một user.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where u.id = :id")
	Optional<User> findByIdForUpdate(@Param("id") String id);

	/** Tăng bộ đếm đăng nhập sai bằng UPDATE nguyên tử (tránh mất tăng khi song song). */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update User u set u.failedLoginAttempts = u.failedLoginAttempts + 1 where u.id = :id")
	void incrementFailedLoginAttempts(@Param("id") String id);

	/** Khoá tài khoản tới {@code until} và reset bộ đếm. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update User u set u.lockedUntil = :until, u.failedLoginAttempts = 0 where u.id = :id")
	void lockUntil(@Param("id") String id, @Param("until") Instant until);

	/** Mở khoá + reset bộ đếm khi đăng nhập thành công. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update User u set u.lockedUntil = null, u.failedLoginAttempts = 0 where u.id = :id")
	void clearLock(@Param("id") String id);

	/**
	 * Admin khoá tài khoản (FR-USER-04). Chỉ ghi khi tài khoản CHƯA bị khoá — trả về
	 * 0 nghĩa là đã bị khoá từ trước (giữ nguyên thời điểm + lý do lần khoá đầu).
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update User u
			   set u.suspendedAt = :now, u.suspendedReason = :reason, u.suspendedBy = :by, u.updatedAt = :now
			 where u.id = :id and u.suspendedAt is null
			""")
	int suspendIfActive(@Param("id") String id, @Param("now") Instant now,
			@Param("reason") String reason, @Param("by") String suspendedBy);

	/**
	 * Admin mở khoá. Gỡ luôn khoá tạm do đăng nhập sai (FR-AUTH-02): người dùng nhờ
	 * Admin mở khoá thì mong vào được ngay, không phải chờ thêm 15 phút.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update User u
			   set u.suspendedAt = null, u.suspendedReason = null, u.suspendedBy = null,
			       u.lockedUntil = null, u.failedLoginAttempts = 0, u.updatedAt = :now
			 where u.id = :id
			""")
	void unsuspend(@Param("id") String id, @Param("now") Instant now);
}
