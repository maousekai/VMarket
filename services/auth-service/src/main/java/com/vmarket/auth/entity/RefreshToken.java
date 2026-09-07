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
 * Refresh token đã phát cho một phiên đăng nhập.
 *
 * <p>Chỉ lưu <b>hash</b> của token (SHA-256), không lưu giá trị gốc — log và DB
 * không được chứa token (NFR-SEC-06).
 *
 * <p>Xoay vòng (FR-AUTH-02): mỗi lần {@code /refresh} thu hồi token hiện tại
 * ({@code revoked_at}) và trỏ {@code replaced_by} sang token mới. Dùng lại một
 * token đã {@code revoked_at} = dấu hiệu bị đánh cắp → thu hồi toàn bộ phiên.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
public class RefreshToken extends BaseEntity {

	@Column(name = "user_id", nullable = false, length = BaseEntity.ID_LENGTH)
	private String userId;

	@Column(name = "token_hash", nullable = false, unique = true, length = 255)
	private String tokenHash;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	/** Thời điểm token bị thu hồi (rotate / logout / phát hiện reuse). {@code null} = còn hiệu lực. */
	@Column(name = "revoked_at")
	private Instant revokedAt;

	/** Id của refresh token thay thế token này khi xoay vòng (audit). */
	@Column(name = "replaced_by", length = BaseEntity.ID_LENGTH)
	private String replacedBy;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;
}
