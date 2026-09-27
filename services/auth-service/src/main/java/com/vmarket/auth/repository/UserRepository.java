package com.vmarket.auth.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.vmarket.auth.entity.RoleName;
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
	 *
	 * <p>FR-USER-04: mọi luồng đổi tài khoản hoặc phát / xoay refresh token (đăng nhập,
	 * OTP, refresh, đổi / đặt lại mật khẩu) và Admin khoá / mở khoá đều nạp user qua
	 * khoá này, nên chúng chạy <b>lần lượt</b> trên cùng một user: một luồng đã đọc
	 * "chưa bị khoá" thì Admin phải chờ nó commit xong mới khoá được (rồi thu hồi luôn
	 * token nó vừa phát); Admin khoá trước thì luồng kia chờ và đọc được trạng thái mới.
	 *
	 * <p><b>Phải là lần nạp user ĐẦU TIÊN trong transaction.</b> Nếu entity đã nằm trong
	 * persistence context, Hibernate vẫn khoá dòng nhưng trả lại đúng object cũ, không
	 * nạp lại dữ liệu — tức là khoá xong mà vẫn cầm trạng thái cũ.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where u.id = :id")
	Optional<User> findByIdForUpdate(@Param("id") String id);

	/** Như {@link #findByIdForUpdate} nhưng tìm theo email (đăng nhập, OTP, đặt lại mật khẩu). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where u.email = :email")
	Optional<User> findByEmailForUpdate(@Param("email") String email);

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
	 * Đổi mật khẩu (FR-USER-03) + gỡ khoá tạm do đăng nhập sai.
	 *
	 * <p>UPDATE đúng các cột cần đổi, không {@code save(user)} cả entity: lưu cả entity
	 * là ghi lại mọi cột theo ảnh chụp lúc đọc, kể cả {@code suspended_*} — Admin khoá
	 * xen vào giữa lúc đọc và lúc lưu sẽ bị ghi đè mất (review PR #22, M1). Dòng user
	 * cũng đã bị khoá bằng {@link #findByIdForUpdate}; đây là lớp bảo vệ thứ hai.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update User u
			   set u.passwordHash = :hash, u.failedLoginAttempts = 0, u.lockedUntil = null, u.updatedAt = :now
			 where u.id = :id
			""")
	void updatePasswordAndClearLock(@Param("id") String id, @Param("hash") String passwordHash,
			@Param("now") Instant now);

	/**
	 * Đặt lại mật khẩu (FR-AUTH-04): như {@link #updatePasswordAndClearLock} và đánh dấu
	 * email đã xác thực (nhận được mã qua email là đã chứng minh sở hữu email).
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update User u
			   set u.passwordHash = :hash, u.emailVerified = true, u.failedLoginAttempts = 0,
			       u.lockedUntil = null, u.updatedAt = :now
			 where u.id = :id
			""")
	void resetPasswordAndClearLock(@Param("id") String id, @Param("hash") String passwordHash,
			@Param("now") Instant now);

	/**
	 * Số dòng khớp "tài khoản {@code id} tồn tại, KHÔNG bị Admin khoá và đang có vai trò
	 * {@code role}" (0 hoặc 1). Dùng kiểm tra Admin thực hiện thao tác quản trị
	 * (FR-USER-04): vai trò trong access token có thể đã cũ tới 15 phút.
	 *
	 * <p>Truy vấn vô hướng nên luôn đọc từ CSDL, không lấy entity cũ trong persistence
	 * context.
	 */
	@Query("""
			select count(u) from User u, UserRole ur, Role r
			 where u.id = :id and u.suspendedAt is null
			   and ur.userId = u.id and r.id = ur.roleId and r.name = :role
			""")
	long countActiveWithRole(@Param("id") String id, @Param("role") RoleName role);

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
	 *
	 * <p>Xoá {@code suspended_*} là đúng: đó chỉ là trạng thái hiện tại. Lần khoá (ai,
	 * lúc nào, lý do) đã nằm trong {@code account_activities} từ lúc khoá.
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
