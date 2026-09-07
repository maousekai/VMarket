package com.vmarket.auth.entity;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Tài khoản người dùng. V1 giữ các cột lõi (định danh + mật khẩu đã hash).
 * Các cột nghiệp vụ bổ sung do migration riêng của từng subtask thêm:
 * <ul>
 *   <li><b>V2 — PBL6-42:</b> {@code email_verified} (kích hoạt tài khoản)</li>
 *   <li><b>V3 — PBL6-43:</b> {@code failed_login_attempts}, {@code locked_until}</li>
 *   <li><b>V4 — PBL6-44:</b> {@code password_hash} chuyển nullable (user tạo qua OTP
 *       không có mật khẩu); thêm bảng {@code email_otp}</li>
 * </ul>
 * (Migration tiếp theo: V5.)
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User extends BaseEntity {

	@Column(nullable = false, unique = true, length = 320)
	private String email;

	@Column(nullable = false, unique = true, length = 50)
	private String username;

	/**
	 * Băm BCrypt — không bao giờ lưu/log mật khẩu dạng rõ (NFR-SEC-01/06).
	 * {@code null} với tài khoản tạo qua luồng OTP (chưa đặt mật khẩu).
	 */
	@Column(name = "password_hash", length = 100)
	private String passwordHash;

	/**
	 * Email đã được xác thực chưa (FR-AUTH-01). User vừa đăng ký = {@code false}
	 * (trạng thái PENDING). Luồng gửi email + endpoint kích hoạt: PBL6-45.
	 */
	@Column(name = "email_verified", nullable = false)
	private boolean emailVerified;

	/** Số lần đăng nhập sai LIÊN TIẾP (FR-AUTH-02). Reset về 0 khi đăng nhập đúng. */
	@Column(name = "failed_login_attempts", nullable = false)
	private int failedLoginAttempts;

	/** Thời điểm hết khoá; {@code null} = không bị khoá. */
	@Column(name = "locked_until")
	private Instant lockedUntil;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
}
