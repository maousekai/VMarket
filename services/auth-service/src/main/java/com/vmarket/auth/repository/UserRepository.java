package com.vmarket.auth.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.vmarket.auth.entity.User;

public interface UserRepository extends JpaRepository<User, String> {

	Optional<User> findByEmail(String email);

	Optional<User> findByUsername(String username);

	boolean existsByEmail(String email);

	boolean existsByUsername(String username);

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
}
