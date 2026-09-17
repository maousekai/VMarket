package com.vmarket.auth.entity;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một lần phát mã đặt lại mật khẩu (FR-AUTH-04). Khác {@link EmailOtp} (key theo
 * email vì user có thể chưa tồn tại), bảng này key theo {@code user_id} — mục
 * tiêu đặt lại mật khẩu luôn là một {@link User} đã tồn tại.
 */
@Entity
@Table(name = "password_reset_token")
@Getter
@Setter
@NoArgsConstructor
public class PasswordResetToken extends BaseEntity {

	@Column(name = "user_id", nullable = false, length = BaseEntity.ID_LENGTH)
	private String userId;

	/** Băm BCrypt của mã 6 số — không bao giờ lưu mã dạng rõ. */
	@Column(name = "code_hash", nullable = false, length = 255)
	private String codeHash;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	/** Số lần nhập sai liên tiếp cho mã này. */
	@Column(name = "attempts", nullable = false)
	private int attempts;

	/** Thời điểm mã được dùng / bị vô hiệu; {@code null} = còn hiệu lực. */
	@Column(name = "consumed_at")
	private Instant consumedAt;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;
}
