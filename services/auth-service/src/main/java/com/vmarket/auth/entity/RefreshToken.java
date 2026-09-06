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
 * Refresh token đã phát cho một phiên đăng nhập. V1 chỉ có cột lõi; cột phục vụ
 * thu hồi / xoay vòng token ({@code revoked_at}, {@code replaced_by}...) sẽ do
 * migration của PBL6-43 / PBL6-46 bổ sung.
 *
 * <p>Chỉ lưu <b>hash</b> của token (SHA-256), không lưu giá trị gốc — log và DB
 * không được chứa token (NFR-SEC-06).
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
public class RefreshToken extends BaseEntity {

	@Column(name = "user_id", nullable = false, length = 26)
	private String userId;

	@Column(name = "token_hash", nullable = false, unique = true, length = 255)
	private String tokenHash;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;
}
