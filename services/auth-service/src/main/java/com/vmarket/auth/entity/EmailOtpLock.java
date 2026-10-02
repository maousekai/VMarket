package com.vmarket.auth.entity;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Một dòng / một email từng xin OTP — chỉ dùng làm đối tượng khoá
 * ({@code SELECT ... FOR UPDATE}) để tuần tự hoá việc phát OTP cho cùng một email,
 * kể cả email chưa có tài khoản (không có dòng {@code users} để khoá).
 */
@Entity
@Table(name = "email_otp_lock")
@Getter
@NoArgsConstructor
public class EmailOtpLock {

	@Id
	@Column(length = 320)
	private String email;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public EmailOtpLock(String email) {
		this.email = email;
	}
}
