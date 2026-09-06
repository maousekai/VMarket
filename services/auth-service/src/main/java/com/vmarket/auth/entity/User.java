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
 *   <li><b>V2 — PBL6-42:</b> {@code email_verified} (kích hoạt tài khoản) — đã có</li>
 *   <li>PBL6-43: {@code failed_login_attempts}, {@code locked_until} (migration V3)</li>
 *   <li>PBL6-44: {@code provider}, {@code provider_user_id} (Google OAuth2)</li>
 * </ul>
 * (Header của {@code V1__init_auth_schema.sql} có kế hoạch đánh số cũ — nay đã đổi,
 * migration tiếp theo là V3.)
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

	/** Băm BCrypt — không bao giờ lưu/log mật khẩu dạng rõ (NFR-SEC-01/06). */
	@Column(name = "password_hash", nullable = false, length = 100)
	private String passwordHash;

	/**
	 * Email đã được xác thực chưa (FR-AUTH-01). User vừa đăng ký = {@code false}
	 * (trạng thái PENDING). Luồng gửi email + endpoint kích hoạt: PBL6-45.
	 */
	@Column(name = "email_verified", nullable = false)
	private boolean emailVerified;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
}
