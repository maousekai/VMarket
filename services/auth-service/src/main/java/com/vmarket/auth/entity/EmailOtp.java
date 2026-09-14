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
 * Một lần phát mã OTP xác thực email (FR-AUTH-01). Key theo {@code email} vì tại
 * thời điểm phát mã, user có thể chưa tồn tại.
 *
 * <p>Chỉ lưu <b>hash BCrypt</b> của mã 6 số — DB lộ vẫn không lấy được mã, và mã
 * không bao giờ được ghi log (NFR-SEC-06).
 */
@Entity
@Table(name = "email_otp")
@Getter
@Setter
@NoArgsConstructor
public class EmailOtp extends BaseEntity {

	@Column(nullable = false, length = 320)
	private String email;

	@Column(name = "code_hash", nullable = false, length = 255)
	private String codeHash;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	/** Số lần nhập sai; {@code >= maxAttempts} → mã bị vô hiệu. */
	@Column(nullable = false)
	private int attempts;

	/** {@code != null} → mã đã dùng hoặc đã bị vô hiệu hoá. */
	@Column(name = "consumed_at")
	private Instant consumedAt;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;
}
